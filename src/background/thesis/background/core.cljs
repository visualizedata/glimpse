(ns thesis.background.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [goog.string :as gstring]
            [goog.string.format]
            [cljs.core.async :refer [<! chan]]
            [chromex.logging :refer-macros [log info warn error group group-end]]
            [chromex.support :refer-macros [oget]]
            [chromex.chrome-event-channel :refer [make-chrome-event-channel]]
            [chromex.protocols :refer [post-message! get-sender]]
            [chromex.ext.tabs :as tabs]
            [chromex.ext.runtime :as runtime]
            [chromex.ext.web-request :as web-request]
            [chromex.ext.browser-action :as browser-action]
            [chromex.ext.storage :as storage]
            [thesis.background.storage :as t-storage :refer [process-request! setup-storage! get-and-store-psl! get-domain-count]]
            [thesis.background.location :as location]))

(enable-console-print!)
(def clients (atom []))
(def location (atom nil))
(declare tell-client-about-click!)

; -- clients manipulation ---------------------------------------------------------------------------------------------------

(defn add-client! [client]
  ;(log "BACKGROUND: client connected" (get-sender client))
  (t-storage/tabdict-add-client (.. (get-sender client) -tab -id))
  (swap! clients conj client))

(defn remove-client! [client]
  ;(log "BACKGROUND: client disconnected" (get-sender client))
  (t-storage/tabdict-remove-client (.. (get-sender client) -tab -id))
  (let [remove-item (fn [coll item] (remove #(identical? item %) coll))]
    (swap! clients remove-item client)))

; -- client event loop ------------------------------------------------------------------------------------------------------

(defn run-client-message-loop! [client]
  (go-loop []
    (when-let [message (<! client)]
      (let [tabId (.. (get-sender client) -tab -id)]
        (condp = message
          "ind-clicked!" (tell-client-about-click! tabId)
          "get-counts" (log (str tabId " wants counts"))
          (log message)))
      ;(if (= "ind-clicked!" message) (tell-client-about-click! (.. (get-sender client) -tab -id)))
      ;(log "BACKGROUND: got client message:" message "from tab: " (.. (get-sender client) -tab -id))
      (recur))
    (remove-client! client)))

; -- event handlers ---------------------------------------------------------------------------------------------------------

(defn handle-client-connection! [client]
  (add-client! client)
  (post-message! client "ACK")
  (run-client-message-loop! client))

(defn tell-clients-about-new-tab! []
  (doseq [client @clients]
    (post-message! client "a new tab was created")))

(defn message-to-client [tabId msg]
  (doseq [client @clients]
    (if (= tabId (.. (get-sender client) -tab -id))
      (post-message! client msg))))

(defn tell-client-about-click! [id]
  (.. js/chrome -tabs (captureVisibleTab
                        #js {"quality" 50}
                        #(message-to-client id (clj->js {:type "init" :id id :img % :tabdict (t-storage/get-tabdict id)})))))

(defn tell-client-about-request! [r]
  (message-to-client (.-tabId r) (clj->js {:type "new-request" :tabdict (t-storage/get-tabdict (.-tabId r))})))

; -- main event loop --------------------------------------------------------------------------------------------------------

(defn process-chrome-event [event-num event]
  (let [[event-id event-args] event]
    ;(log (gstring/format "BACKGROUND: got chrome event (%05d)" event-num) (str event-id))
    (case event-id
      ::browser-action/on-clicked (do
                                    (get-domain-count "zeit.de")
                                    (tell-client-about-click! (oget (first event-args) "id")))
      ::storage/on-changed (.. js/chrome -storage -local (get #(reset! location %)))
      ::web-request/on-before-request (let [req (first event-args)]
                                        (tell-client-about-request! req)
                                        (process-request! req @location))
      ::runtime/on-connect (apply handle-client-connection! event-args)
      ::tabs/on-created (tell-clients-about-new-tab!)
      nil)))

(defn run-chrome-event-loop! [chrome-event-channel]
  (log "BACKGROUND: starting main event loop...")
  (go-loop [event-num 1]
    (when-let [event (<! chrome-event-channel)]
      (process-chrome-event event-num event)
      (recur (inc event-num)))
    (log "BACKGROUND: leaving main event loop")))

(defn boot-chrome-event-loop! []
  (let [chrome-event-channel (make-chrome-event-channel (chan))]
    (tabs/tap-all-events chrome-event-channel)
    (runtime/tap-all-events chrome-event-channel)
    (storage/tap-all-events chrome-event-channel)
    (browser-action/tap-on-clicked-events chrome-event-channel)
    (web-request/tap-on-before-request-events chrome-event-channel (clj->js {:urls ["http://*/*", "https://*/*"]}))
    (run-chrome-event-loop! chrome-event-channel)))

; -- main entry point -------------------------------------------------------------------------------------------------------

(defn init! []
  (log "BACKGROUND: init")
  (location/watch-location!)
  (setup-storage!)
  (get-and-store-psl!)
  (boot-chrome-event-loop!))
