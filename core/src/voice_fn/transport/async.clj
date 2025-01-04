(ns voice-fn.transport.async
  (:require
   [clojure.core.async :as a]
   [taoensso.telemere :as t]
   [voice-fn.frame :as frame]
   [voice-fn.pipeline :as pipeline]
   [voice-fn.transport.protocols :as tp]))

(defn- input->frame
  ([input]
   (if (frame/audio-input-raw? input)
     input
     (throw (ex-info "Input is not a valid input frame. Please provide a serializer"
                     {:input input
                      :cause :error/transport-missing-serializer}))))
  ([input serializer]
   (if (nil? serializer)
     (input->frame input)
     (if (frame/audio-input-raw? input)
       input
       (tp/deserialize-frame serializer input)))))

(defmethod pipeline/process-frame :transport/async-input
  [processor-type pipeline _ frame]
  (let [{:transport/keys [in-ch serializer]} (:pipeline/config @pipeline)
        running? (atom false)]
    (cond
      (frame/system-start? frame)
      (do
        (t/log! {:level :info
                 :id processor-type} "Staring transport input")
        (reset! running? true)
        (a/go-loop []
          (when running?
            (when-let [input (a/<! in-ch)]
              (when-let [input-frame (try
                                       (input->frame input serializer)
                                       (catch clojure.lang.ExceptionInfo e
                                         (let [data (merge (ex-data e)
                                                           {:message (ex-message e)})]
                                           (pipeline/send-frame! pipeline (frame/system-error data)))))]
                (a/>! (:pipeline/main-ch @pipeline) input-frame))
              (recur)))))
      (frame/system-stop frame)
      (do (t/log! {:level :info
                   :id processor-type} "Stopping transport input")
        (reset! running? false)))))

(defmethod pipeline/process-frame :transport/async-output
  [type pipeline _ frame]
  (t/log! {:level :debug
           :id type}
    ["Output frame" (:frame/data frame)])
  (let [{:transport/keys [out-ch serializer]} (:pipeline/config @pipeline)]
    (cond
      (frame/audio-output-raw? frame)
      (when-let [output (if serializer
                          (tp/serialize-frame serializer frame)
                          frame)]
        (a/put! out-ch output))
      (frame/system-stop? frame) (a/close! out-ch))))
