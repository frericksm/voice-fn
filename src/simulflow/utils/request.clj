(ns simulflow.utils.request
  "Taken from https://github.com/wkok/openai-clojure/blob/main/src/wkok/openai_clojure/sse.clj."
  {:no-doc true}
  (:require
   [clojure.core.async :as a]
   [clojure.string :as string]
   [hato.client :as http]
   [hato.middleware :as hm]
   [simulflow.async :refer [vthread vthread-loop]]
   [simulflow.utils.core :as u])
  (:import
   (java.io InputStream)))

(def event-mask (re-pattern (str "(?s).+?\n\n")))

(defn deliver-events
  [events {:keys [on-next]}]
  (when on-next
    (vthread-loop []
      (when-let [event (a/<!! events)]
        (when (not= :done event)
          (on-next event)
          (recur))))))

(defn- parse-openai-event [raw-event]
  (let [data-idx (string/index-of raw-event "{")
        done-idx (string/index-of raw-event "[DONE]")]
    (if done-idx
      :done
      (-> (subs raw-event data-idx)
          (u/parse-if-json :throw-on-error? true)))))

; Per this discussion: https://community.openai.com/t/clarification-for-max-tokens/19576
; if the max_tokens is not provided, the response will try to use all the available
; tokens to generate response, hence DEFAULT_BUFFER_SIZE should be large enough
(def ^:private DEFAULT_BUFFER_SIZE 100000)

(defn calc-buffer-size
  "Buffer size should be at least equal to max_tokens
  plus the [DONE] terminator"
  [{:keys [max_tokens]
    :or {max_tokens DEFAULT_BUFFER_SIZE}}]
  (inc max_tokens))

(defn sse-events
  "Returns a core.async channel with events as clojure data structures.
  Inspiration from https://gist.github.com/oliyh/2b9b9107e7e7e12d4a60e79a19d056ee"
  [{:keys [request params]}]
  (let [close? (:stream/close? params)
        parse-event (or (:parse-event params) parse-openai-event)
        event-stream ^InputStream (:body (http/request (merge request
                                                              params
                                                              {:as :stream})))
        buffer-size (calc-buffer-size params)
        events (a/chan (a/buffer buffer-size) (map parse-event))]
    (vthread
      (try
        (loop [byte-coll []]
          (let [byte-arr (byte-array (max 1 (.available event-stream)))
                bytes-read (.read event-stream byte-arr)]

            (if (neg? bytes-read)

              ;; Input stream closed, exiting read-loop
              nil

              (let [next-byte-coll (concat byte-coll (seq byte-arr))
                    data (slurp (byte-array next-byte-coll))]
                (if-let [es (not-empty (re-seq event-mask data))]
                  (if (every? true? (map #(a/>!! events %) es))
                    (recur (drop (apply + (map #(count (.getBytes ^String %)) es))
                                 next-byte-coll))

                    ;; Output stream closed, exiting read-loop
                    nil)

                  (recur next-byte-coll))))))
        (finally
          (when close?
            (a/close! events))
          (.close event-stream))))

    events))

(defn sse-request
  "Process streamed results.
  If on-next callback provided, then read from channel and call the callback.
  Returns a response with the core.async channel as the body"
  [{:keys [params] :as ctx}]
  (let [events (sse-events ctx)]
    (deliver-events events params)
    {:status 200
     :body events}))

(defn wrap-trace
  "Middleware that allows the user to supply a trace function that
  will receive the raw request & response as arguments.
  See: https://github.com/gnarroway/hato?tab=readme-ov-file#custom-middleware"
  [trace]
  (fn [client]
    (fn
      ([req]
       (let [resp (client req)]
         (trace req resp)
         resp))
      ([req respond raise]
       (client req
               #(respond (do (trace req %)
                             %))
               raise)))))

(defn with-trace-middleware
  "The default list of middleware hato uses for wrapping requests but
  with added wrap-trace in the correct position to allow tracing of error messages."
  [trace]
  [hm/wrap-request-timing

   hm/wrap-query-params
   hm/wrap-basic-auth
   hm/wrap-oauth
   hm/wrap-user-info
   hm/wrap-url

   hm/wrap-decompression
   hm/wrap-output-coercion

   (wrap-trace trace)

   hm/wrap-exceptions
   hm/wrap-accept
   hm/wrap-accept-encoding
   hm/wrap-multipart

   hm/wrap-content-type
   hm/wrap-form-params
   hm/wrap-nested-params
   hm/wrap-method])

(def openai-completions-url "https://api.openai.com/v1/chat/completions")

(defn stream-chat-completion
  [{:keys [api-key messages tools model response-format completions-url]
    :or {model "gpt-4o-mini"
         completions-url openai-completions-url}}]
  (:body (sse-request {:request {:url completions-url
                                 :headers {"Authorization" (str "Bearer " api-key)
                                           "Content-Type" "application/json"}

                                 :method :post
                                 :body (u/json-str (cond-> {:messages messages
                                                            :stream true
                                                            :response_format response-format
                                                            :model model}
                                                     (pos? (count tools)) (assoc :tools tools)))}
                       :params {:stream/close? true}})))

(defn normal-chat-completion
  [{:keys [api-key messages tools model response-format stream completions-url]
    :or {model "gpt-4o-mini"
         stream false}}]
  (http/request {:url openai-completions-url
                 :headers {"Authorization" (str "Bearer " api-key)
                           "Content-Type" "application/json"}

                 :throw-on-error? false
                 :method :post
                 :body (u/json-str (cond-> {:messages messages
                                            :stream stream
                                            :response_format response-format
                                            :model model}
                                     (pos? (count tools)) (assoc :tools tools)))}))

(comment
  (require '[simulflow.secrets :refer [secret]])

  (http/request {:url "https://api.openai.com/v1/chat/completions"
                 :headers {"Authorization" (str "Bearer " (secret [:openai :new-api-sk]))
                           "Content-Type" "application/json"}

                 :method :post
                 :body (u/json-str {:messages [{:role "system" :content "You are a helpful assistant"}
                                               {:role "user" :content "What is the capital of France?"}]
                                    :model "gpt-4o-mini"})})

  (def res (sse-request
             {:request {:url "https://api.openai.com/v1/chat/completions"
                        :headers {"Authorization" (str "Bearer " (secret [:openai :new-api-sk]))
                                  "Content-Type" "application/json"}

                        :method :post
                        :body (u/json-str {:messages [{:role "system" :content "You are a helpful assistant"}
                                                      {:role "user" :content "What is the capital of France?"}]
                                           :stream true
                                           :model "gpt-4o-mini"})}
              :params {:stream/close? true}}))

  res

  (def ch (:body res))

  (a/go-loop []
    (println "Taking from result")
    (when-let  [res (a/<! ch)]
      (println res)
      (recur)))

  ,)
