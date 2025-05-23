(ns voice-fn.transport
  (:require
   [clojure.core.async :as a]
   [clojure.core.async.flow :as flow]
   [taoensso.telemere :as t]
   [uncomplicate.clojure-sound.core :refer [open! read! start! stop! write!]]
   [uncomplicate.clojure-sound.sampled :refer [audio-format flush! line line-info]]
   [uncomplicate.commons.core :refer [close!]]
   [voice-fn.frame :as frame]
   [voice-fn.schema :as schema]
   [voice-fn.transport.protocols :as tp]
   [voice-fn.transport.serializers :refer [make-twilio-serializer]]
   [voice-fn.utils.audio :as au]
   [voice-fn.utils.core :as u])
  (:import
   (java.util Arrays)
   (javax.sound.sampled AudioFormat AudioSystem DataLine$Info)))

(def AsyncOutputProcessorSchema
  [:map
   [:transport/sample-rate schema/SampleRate]
   [:transport/sample-size-bits schema/SampleSizeBits]
   [:transport/channels schema/AudioChannels]
   [:transport/supports-interrupt? :boolean]
   [:transport/audio-chunk-duration :int] ;; duration in ms for each audio chunk. Default 20ms
   [:transport/audio-chunk-size :int]
   [:transport/out-ch schema/CoreAsyncChannel]])

(defn realtime-out-transform
  [{:transport/keys [serializer] :as state} in msg]
  (if (= in :events)
    [state {:out [msg]}]
    (cond
      (frame/audio-output-raw? msg)
      [state {:audio-write [(if serializer
                              (tp/serialize-frame serializer msg)
                              msg)]}]

      (frame/system-config-change? msg)
      (if-let [serializer (:transport/serializer (:frame/data msg))]
        [(assoc state :transport/serializer serializer)]
        [state])

      :else [state])))

(defn twilio-transport-in-transform
  [{:twilio/keys [handle-event] :as state} _ input]
  (let [data (u/parse-if-json input)
        output (if (fn? handle-event) (handle-event data) nil)
        out-frames (partial merge-with into output)]
    (condp = (:event data)
      "start" [state (if-let [stream-sid (:streamSid data)]
                       (out-frames {:sys-out [(frame/system-config-change
                                                (u/without-nils {:twilio/stream-sid stream-sid
                                                                 :twilio/call-sid (get-in data [:start :callSid])
                                                                 :transport/serializer (make-twilio-serializer stream-sid)}))]})
                       (out-frames {}))]
      "media"
      [state (out-frames {:out [(frame/audio-input-raw
                                  (u/decode-base64 (get-in data [:media :payload])))]})]

      "close"
      [state (out-frames {:sys-out [(frame/system-stop true)]})]
      [state])))

(defn async-in-transform
  [state _ input]
  (if (bytes? input)
    [state {:out [(frame/audio-input-raw input)]}]
    [state]))

(defn line-supported?
  [^DataLine$Info info]
  (AudioSystem/isLineSupported info))

(defn open-line!
  "Opens the microphone with specified format. Returns the TargetDataLine."
  [line-type ^AudioFormat format]
  (assert (#{:target :source} line-type) "Invalid line type")
  (let [info (line-info line-type format)
        line (line info)]
    (when-not (line-supported? info)
      (throw (ex-info "Audio line not supported"
                      {:format format})))
    (open! line format)
    (start! line)
    line))

(defn- frame-buffer-size
  "Get read buffer size based on the sample rate for input"
  [sample-rate]
  (* 2 (/ sample-rate 100)))

;; =============================================================================
;; Processors

(def audio-splitter
  "Takes in audio-output-raw frames and splits them up into :audio.out/duration-ms
  chunks. Chunks are split to achieve realtime streaming."
  (flow/process
    {:describe (fn [] {:ins {:in "Channel for raw audio frames"}
                       :outs {:out "Channel for audio frames split by chunk size"}
                       :params {:audio.out/chunk-size "The chunk size by which to split each audio
     frame. Specify either this or the other parameters so that chunk size can be computed"

                                :audio.out/sample-rate "Sample rate of the output audio"
                                :audio.out/sample-size-bits "Size in bits for each sample"
                                :audio.out/channels "Number of channels. 1 or 2 (mono or stereo audio)"
                                :audio.out/duration-ms "Duration in ms of each chunk that will be streamed to output"}})

     :init (fn [{:audio.out/keys [chunk-size sample-rate sample-size-bits channels duration-ms]}]
             (assert (or chunk-size (and sample-rate sample-size-bits channels duration-ms))
                     "Either provide :audio.out/chunk-size or sample-rate, sample-size-bits, channels and chunk duration for the size to be computed")
             {:audio.out/chunk-size (or chunk-size (au/audio-chunk-size {:sample-rate sample-rate
                                                                         :sample-size-bits sample-size-bits
                                                                         :channels channels
                                                                         :duration-ms duration-ms}))})
     :transform (fn [{:audio.out/keys [chunk-size] :as state} _ frame]
                  (cond
                    (frame/audio-output-raw? frame)
                    (loop [audio (:frame/data frame)
                           chunks []]
                      (let [audio-size (count audio)
                            chunk-actual-size (min chunk-size audio-size)
                            chunk (byte-array chunk-actual-size)]
                        ;; Copy chunk-size amount of data into next chunk
                        (System/arraycopy audio 0 chunk 0 chunk-actual-size)
                        (if (> audio-size chunk-actual-size)
                          (let [new-audio-size (- audio-size chunk-actual-size)
                                remaining-audio (byte-array new-audio-size)]
                            (System/arraycopy audio chunk-actual-size remaining-audio 0 new-audio-size)
                            (recur remaining-audio (conj chunks (frame/audio-output-raw chunk))))
                          ;; No more chunks to process, return final result
                          [state {:out (conj chunks (frame/audio-output-raw chunk))}])))

                    :else [state]))}))

(def twilio-transport-in
  "Takes in twilio events and transforms them into audio-input-raw and config
  changes."
  (flow/process
    {:describe (fn [] {:outs {:sys-out "Channel for system messages that have priority"
                              :out "Channel on which audio frames are put"
                              :speak-out "Channel for speak frames. Used when the user joins the conversation"}
                       :params {:transport/in-ch "Channel from which input comes"
                                :twilio/handle-event "Optional function to be called when a new twilio event is received. Return a map like {cid [frame1 frame2]} to put new frames on the pipeline"}})

     :init (fn [{:transport/keys [in-ch] :twilio/keys [handle-event]}]
             {::flow/in-ports {:twilio-in in-ch}
              :twilio/handle-event handle-event})

     :transform twilio-transport-in-transform}))

(def async-transport-in
  "Takes in twilio events and transforms them into audio-input-raw and config
  changes."
  (flow/process
    {:describe (fn [] {:outs {:out "Channel on which audio frames are put"}
                       :params {:transport/in-ch "Channel from which input comes. Input should be byte array"}})

     :transition (fn [{::flow/keys [in-ports out-ports]} transition]
                   (when (= transition ::flow/stop)
                     (doseq [port (remove nil? (concat (vals in-ports) (vals out-ports)))]
                       (a/close! port))))

     :init (fn [{:transport/keys [in-ch] :twilio/keys [handle-event]}]
             {::flow/in-ports {:in in-ch}})

     :transform async-in-transform}))

(def realtime-transport-out-processor
  "Processor that streams audio out in real time so we can account for
  interruptions."
  (flow/process
    {:describe (fn [] {:ins {:in "Channel for audio output frames "
                             :sys-in "Channel for system messages"}
                       :outs {:out "Channel for bot speech status frames"}
                       :params {:transport/out-chan "Channel on which to put buffered serialized audio"
                                :audio.out/duration-ms "Duration of each audio chunk. Defaults to 20ms"
                                :transport/supports-interrupt? "Whether the processor supports interrupt or not"}})

     :transition (fn [{::flow/keys [in-ports out-ports]} transition]
                   (when (= transition ::flow/stop)
                     (doseq [port (concat (vals in-ports) (vals out-ports))]
                       (a/close! port))))

     :init (fn [{:audio.out/keys [duration-ms]
                 :transport/keys [out-chan]}]
             (assert out-chan "Required :transport/out-chan for sending output")
             (let [;; send every 10ms to account for network
                   duration (or duration-ms 20)
                   sending-interval (* duration 1.1)
                   next-send-time (atom (u/mono-time))

                   ;; Track bot speaking state
                   speaking? (atom false)
                   last-audio-time (atom 0)

                   audio-write-c (a/chan 1024)
                   events-chan (a/chan 1024)
                   realtime-loop #(loop []
                                    (when-let [msg (a/<!! audio-write-c)]
                                      (let [now (u/mono-time)]
                                        (reset! last-audio-time now)

                                        ;; Check if we need to emit bot started speaking
                                        (when (not @speaking?)
                                          (reset! speaking? true)
                                          (a/>!! events-chan
                                                 (frame/bot-speech-start true)))

                                        ;; Send audio with timing control
                                        (a/<!! (a/timeout (- @next-send-time now)))
                                        (t/log! {:level :trace :id :transport} "Sending realtime out")
                                        (a/>!! out-chan msg)
                                        (reset! next-send-time (+ now sending-interval)))
                                      (recur)))

                   ;; Monitor for end of speech
                   speech-monitor #(loop []
                                     (a/<!! (a/timeout 1000)) ;; Check every 1000ms
                                     (let [now (u/mono-time)
                                           silence-duration (- now @last-audio-time)]
                                       ;; If we've been silent for 2x chunk duration and were speaking
                                       (when (and @speaking?
                                                  (> silence-duration (* 4 duration)))
                                         (reset! speaking? false)
                                         (a/>!! events-chan (frame/bot-speech-stop true)))
                                       (recur)))]

               ;; Start both background processes
               ((flow/futurize realtime-loop :exec :io))
               ((flow/futurize speech-monitor :exec :io))

               {::flow/out-ports {:audio-write audio-write-c}
                ::flow/in-ports {:events events-chan}
                :bot/speaking? speaking?
                :bot/last-audio-time last-audio-time}))

     :transform realtime-out-transform}))

(def microphone-transport-in
  "Records microphone and sends raw-audio-input frames down the pipeline."
  (flow/process
    {:describe (fn [] {:outs {:out "Channel on which audio frames are put"}
                       :params {:audio-in/sample-rate "Sample rate for audio input. Default 16000"
                                :audio-in/channels "Channels for audio input. Default 1"
                                :audio-in/sample-size-bits "Sample size in bits. Default 16"}})
     :init (fn [{:audio-in/keys [sample-rate sample-size-bits channels]
                 :or {sample-rate 16000
                      channels 1
                      sample-size-bits 16}}]
             (let [buffer-size (frame-buffer-size sample-rate)
                   af (audio-format sample-rate sample-size-bits channels)
                   line (open-line! :target af)
                   out-ch (a/chan 1024)
                   buffer (byte-array buffer-size)
                   running? (atom true)
                   close #(do
                            (reset! running? false)
                            (a/close! out-ch)
                            (stop! line)
                            (close! line))
                   recording-loop #(do
                                     (while @running?
                                       (let [bytes-read (read! line buffer 0 buffer-size)]
                                         (when (pos? bytes-read)
                                           (let [audio-data (Arrays/copyOfRange buffer 0 bytes-read)]
                                             (a/>!! out-ch (frame/audio-input-raw audio-data)))))))]
               ((flow/futurize recording-loop :exec :io))
               {::flow/in-ports {:mic-in out-ch}
                :audio-in/sample-size-bits sample-size-bits
                :audio-in/sample-rate sample-rate
                :audio-in/channels channels
                ::close close}))
     :transition (fn [state transiton]
                   (when (and (= transiton ::flow/stop)
                              (fn? (::close state)))
                     (t/log! :info "Closing transport in")
                     ((::close state))))
     :transform (fn [state _ frame]
                  [state {:out [frame]}])}))

(def realtime-speakers-out-processor
  "Processor that streams audio out in real time so we can account for
  interruptions."
  (flow/process
    {:describe (fn [] {:ins {:in "Channel for audio output frames "
                             :sys-in "Channel for system messages"}
                       :outs {:out "Channel for bot speech status frames"}
                       :params {:audio.out/sample-rate "Sample rate of the output audio"
                                :audio.out/sample-size-bits "Size in bits for each sample"
                                :audio.out/channels "Number of channels. 1 or 2 (mono or stereo audio)"
                                :audio.out/duration-ms "Duration in ms of each chunk that will be streamed to output"}})

     :transition (fn [{::flow/keys [in-ports out-ports] :as state} transition]
                   (when (= transition ::flow/stop)
                     (when-let [line (::speaker-line state)]
                       (stop! line)
                       (flush! line)
                       (close! line))
                     (doseq [port (concat (vals in-ports) (vals out-ports))]
                       (a/close! port))))
     :init (fn [{:audio.out/keys [duration-ms sample-rate sample-size-bits channels]
                 :or {sample-rate 16000
                      channels 1
                      sample-size-bits 16}}]

             (let [;; send every 10ms to account for network
                   duration (or duration-ms 20)
                   sending-interval (/ duration 2)
                   next-send-time (atom (u/mono-time))

                   ;; Track bot speaking state
                   speaking? (atom false)
                   last-audio-time (atom 0)

                   line (open-line! :source (audio-format sample-rate sample-size-bits channels))
                   audio-write-c (a/chan 1024)
                   events-chan (a/chan 1024)

                   realtime-loop #(loop []
                                    (when-let [msg (a/<!! audio-write-c)]
                                      (assert (frame/audio-output-raw? msg) "Only audio-output-raw frames can be played to speakers.")
                                      (let [now (u/mono-time)]
                                        (reset! last-audio-time now)

                                        ;; Check if we need to emit bot started speaking
                                        (when (not @speaking?)
                                          (reset! speaking? true)
                                          (a/>!! events-chan
                                                 (frame/bot-speech-start true)))

                                        (a/<!! (a/timeout (- @next-send-time now)))
                                        (write! (:frame/data msg) line 0)
                                        (reset! next-send-time (+ now sending-interval)))
                                      (recur)))
                   ;; Monitor for end of speech
                   speech-monitor #(loop []
                                     (a/<!! (a/timeout 1000)) ;; Check every second
                                     (let [now (u/mono-time)
                                           silence-duration (- now @last-audio-time)]
                                       ;; If we've been silent for 2x chunk duration and were speaking
                                       (when (and @speaking?
                                                  (> silence-duration (* 4 duration)))
                                         (reset! speaking? false)
                                         (a/>!! events-chan (frame/bot-speech-stop true)))
                                       (recur)))]
               ((flow/futurize realtime-loop :exec :io))
               ((flow/futurize speech-monitor :exec :io))
               {::flow/out-ports {:audio-write audio-write-c}
                ::flow/in-ports {:events events-chan}
                :bot/speaking? speaking?
                :bot/last-audio-time last-audio-time
                ::speaker-line line}))

     :transform realtime-out-transform}))
