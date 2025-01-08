(ns voice-fn.pipeline
  (:require
   [clojure.core.async :as a :refer [chan go-loop]]
   [malli.core :as m]
   [malli.error :as me]
   [taoensso.telemere :as t]
   [voice-fn.frame :as frame]
   [voice-fn.protocol :as p]
   [voice-fn.schema :as schema]
   [voice-fn.secrets :refer [secret]]))

(defmulti create-processor
  "Creates a new processor instance of the given type.
   Library users can extend this multimethod to add their own
  processors. Processors need to implement the Processor protocol from `voice-fn.protocol`"

  (fn [id] id))

;; Default implementation that throws an informative error
(defmethod create-processor :default
  [id]
  (throw (ex-info (str "Unknown processor " id)
                  {:id id
                   :cause :processor.error/unknown-type})))

(def PipelineConfigSchema
  [:map
   [:audio-in/sample-rate {:default 16000} schema/SampleRate]
   [:audio-in/channels {:default 1} schema/AudioChannels]
   [:audio-in/encoding {:default :pcm-signed} schema/AudioEncoding]
   [:audio-in/sample-size-bits {:default 16} schema/SampleSizeBits]
   [:audio-out/sample-rate {:default 16000} schema/SampleRate]
   [:audio-out/channels {:default 1} schema/AudioChannels]
   [:audio-out/encoding {:default :pcm-signed} schema/AudioEncoding]
   [:audio-out/sample-size-bits {:default 16} schema/SampleSizeBits]
   [:pipeline/language schema/Language]
   [:pipeline/supports-interrupt? {:default false
                                   :optional true} :boolean]
   [:llm/context schema/LLMContext]
   [:transport/in-ch schema/Channel]
   [:transport/out-ch schema/Channel]])

(defn supports-interrupt?
  [pipeline]
  (get-in pipeline [:pipeline/config :pipeline/supports-interrupt?]))

(defn validate-pipeline
  "Validates the pipeline configuration and all processor configs.
   Returns a map with :valid? boolean and :errors containing any validation errors.

   Example return for valid config:
   {:valid? true}

   Example return for invalid config:
   {:valid? false
    :errors {:pipeline {...}           ;; Pipeline config errors
             :processors [{:type :some/processor
                          :errors {...}}]}} ;; Processor specific errors"
  [{pipeline-config :pipeline/config
    processors :pipeline/processors}]
  (let [;; Validate main pipeline config
        pipeline-valid? (m/validate PipelineConfigSchema pipeline-config)
        pipeline-errors (when-not pipeline-valid?
                          (me/humanize (m/explain PipelineConfigSchema pipeline-config)))

        ;; Validate each processor's config
        processor-results
        (for [{:processor/keys [id config]} processors]
          (let [processor (create-processor id)
                schema (p/processor-schema processor)
                processor-config (p/make-processor-config processor pipeline-config config)
                processor-valid? (m/validate schema processor-config)
                processor-errors (when-not processor-valid?
                                   (me/humanize (m/explain schema processor-config)))]
            {:id id
             :valid? processor-valid?
             :errors processor-errors}))

        ;; Check if any processors are invalid
        invalid-processors (filter (comp not :valid?) processor-results)

        ;; Combine all validation results
        all-valid? (and pipeline-valid?
                        (empty? invalid-processors))]

    (cond-> {:valid? all-valid?}

      ;; Add pipeline errors if any
      (not pipeline-valid?)
      (assoc-in [:errors :pipeline] pipeline-errors)

      ;; Add processor errors if any
      (seq invalid-processors)
      (assoc-in [:errors :processors]
                (keep #(when-not (:valid? %)
                         {:type (:type %)
                          :errors (:errors %)})
                      processor-results)))))

(comment
  (def in (a/chan 1))
  (def out (a/chan 1))
  (def test-pipeline-config {:pipeline/config {:audio-in/sample-rate 8000
                                               :audio-in/encoding :ulaw
                                               :audio-in/channels 1
                                               :audio-in/sample-size-bits 8
                                               :audio-out/sample-rate 8000
                                               :audio-out/encoding :ulaw
                                               :audio-out/sample-size-bits 8
                                               :audio-out/channels 1
                                               :pipeline/language :ro
                                               :llm/context [{:role "system" :content  "Ești un agent vocal care funcționează prin telefon. Răspunde doar în limba română și fii succint. Inputul pe care îl primești vine dintr-un sistem de speech to text (transcription) care nu este intotdeauna eficient și poate trimite text neclar. Cere clarificări când nu ești sigur pe ce a spus omul."}]
                                               :transport/in-ch in
                                               :transport/out-ch out}
                             :pipeline/processors
                             [{:processor/type :transport/twilio-input}
                              {:processor/type :transcription/deepgram
                               :processor/config {:transcription/api-key (secret [:deepgram :api-key])
                                                  :transcription/interim-results? true
                                                  :transcription/punctuate? false
                                                  :transcription/vad-events? true
                                                  :transcription/smart-format? true
                                                  :transcription/model :nova-2}}
                              {:processor/type :llm/context-aggregator}
                              {:processor/type :llm/openai
                               :processor/config {:llm/model "gpt-4o-mini"
                                                  :openai/api-key (secret [:openai :new-api-sk])}}
                              {:processor/type :log/text-input
                               :processor/config {}}
                              {:processor/type :llm/sentence-assembler
                               :processor/config {:sentence/end-matcher #"[.?!;:]"}}
                              {:processor/type :tts/elevenlabs
                               :processor/config {:elevenlabs/api-key (secret [:elevenlabs :api-key])
                                                  :elevenlabs/model-id "eleven_flash_v2_5"
                                                  :elevenlabs/voice-id "7sJPxFeMXAVWZloGIqg2"
                                                  :voice/stability 0.5
                                                  :voice/similarity-boost 0.8
                                                  :voice/use-speaker-boost? true}}

                              {:processor/type :transport/async-output
                               :generates/frames #{}}]})

  (validate-pipeline test-pipeline-config)

  ,)

(defn send-frame!
  "Sends a frame to the appropriate channel based on its type"
  [pipeline frame]
  (if (frame/system-frame? frame)
    (a/put! (:pipeline/system-ch @pipeline) frame)
    (a/put! (:pipeline/main-ch @pipeline) frame)))

(defn processor-map
  "Return a mapping of processor id to the actual processor and it's current
  configuration"
  [pipeline]
  (let [pipeline-config (:pipeline/config pipeline)
        processors-config (:pipeline/processors pipeline)]
    (zipmap
      (map :processor/id processors-config)
      (map (fn [{:processor/keys [id config]}]
             (let [processor (create-processor id)]
               {:processor processor
                :config (p/make-processor-config processor pipeline-config config)}))
           processors-config))))

;; Pipeline creation logic here
(defn create-pipeline
  "Creates a new pipeline from the provided configuration.

   Throws ExceptionInfo with :type :invalid-pipeline-config when the configuration
   is invalid. The exception data will contain :errors with detailed validation
   information.

   Returns an atom containing the initialized pipeline state."
  [pipeline-config]
  (let [validation-result (validate-pipeline pipeline-config)]
    (if (:valid? validation-result)
      (let [main-ch (chan 1024)
            system-ch (chan 1024) ;; High priority channel for system frames
            main-pub (a/pub main-ch :frame/type)
            system-pub (a/pub system-ch :frame/type)
            pm (processor-map pipeline-config)
            pipeline (atom (merge
                             {:pipeline/main-ch main-ch
                              :pipeline/system-ch system-ch
                              :pipeline/processors-m pm
                              :pipeline/main-pub main-pub}
                             pipeline-config))]
        ;; Start each processor
        (doseq [{:processor/keys [id]} (:pipeline/processors pipeline-config)]
          (let [{:keys [processor]} (get pm id)
                afs (p/accepted-frames processor)
                processor-ch (chan 1024)
                processor-system-ch (chan 1024)]
            ;; Tap into main channel, filtering for accepted frame types
            (doseq [frame-type afs]
              (a/sub main-pub frame-type processor-ch)
              ;; system frames that take prioriy over other frames
              (a/sub system-pub frame-type processor-system-ch))
            (swap! pipeline assoc-in [id] {:processor/in-ch processor-ch
                                           :processor/system-ch processor-system-ch})))
        pipeline)
      ;; Throw detailed validation error
      (throw (ex-info "Invalid pipeline configuration"
                      {:type :pipeline/invalid-configuration
                       :errors (:errors validation-result)})))))

(defn start-pipeline!
  [pipeline]
  ;; Start each processor
  (doseq [{:processor/keys [id]} (:pipeline/processors @pipeline)]
    (go-loop []
      (let [{:keys [processor config]} (get-in @pipeline [:pipeline/processors-m id])]

        ;; Read from both processor system channel and processor in
        ;; channel. system channel takes priority
        (when-let [[frame] (a/alts! [(get-in @pipeline [id :processor/system-ch])
                                     (get-in @pipeline [id :processor/in-ch])])]
          (when-let [result (p/process-frame processor pipeline config frame)]
            (when (frame/frame? result)
              (send-frame! pipeline result)))
          (recur)))))
  ;; Send start frame
  (t/log! :debug "Starting pipeline")
  (send-frame! pipeline (frame/system-start true)))

  ;; TODO stop all pipeline channels
(defn stop-pipeline!
  [pipeline]
  (t/log! :debug "Stopping pipeline")
  (t/log! :debug ["Conversation so far" (get-in @pipeline [:pipeline/config :llm/context])])
  (send-frame! pipeline (frame/system-stop true)))

(defn close-processor!
  [pipeline id]
  (t/log! {:level :debug
           :id id} "Closing processor")
  (a/close! (get-in @pipeline [id :processor/in-ch])))
