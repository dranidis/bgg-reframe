(ns bbg-reframe.core
  (:require
   [reagent.dom :as rdom]
   [re-frame.core :as re-frame]
   [day8.re-frame.http-fx]

   [bbg-reframe.events :as events]
   [bbg-reframe.network-events :as network-events]

   [bbg-reframe.views :as views]
   [bbg-reframe.routes :as routes]

   [bbg-reframe.config :as config]
   [bbg-reframe.model.localstorage :refer [item-exists? get-item remove-item!]]
   [clojure.tools.reader.edn :refer [read-string]]
   [re-frame.loggers :refer [console]]

   [bbg-reframe.login-view.events :as login-events]
   [re-frame-firebase-nine.fb-reframe :refer [set-browser-session-persistence fb-reframe-config connect-emulator]]
   [re-frame-firebase-nine.firebase-auth :refer [get-auth on-auth-state-changed on-auth-state-changed-callback]]))


(defn dev-setup []
  (when config/debug?
    (println "dev mode")))

(defn ^:dev/after-load mount-root []
  (re-frame/clear-subscription-cache!)
  (let [root-el (.getElementById js/document "app")]
    (rdom/unmount-component-at-node root-el)
    (rdom/render [views/main-panel] root-el)))

(defn init []
    ;; at the beginning so that they are loaded first
  (fb-reframe-config config/fb-reframe-config-map)

  (connect-emulator)

  (get-auth)
  (set-browser-session-persistence)

  (console :log "Deleting bbg-ui-settings from local storage (Remove me!)")
  (remove-item! "bgg-ui-settings")

  (re-frame/dispatch-sync [::events/initialize-db])
  (routes/start!)

  (re-frame/dispatch [::network-events/cors-check])
  (when (and (item-exists? "bgg-user") (item-exists? "bgg-games"))
    (console :log "Using local storage data")
    (re-frame/dispatch [::events/update-user (get-item "bgg-user")])
    (re-frame/dispatch [::events/update-games (read-string (get-item "bgg-games"))]))
  (when (item-exists? "bgg-ui-settings")
    (re-frame/dispatch [::events/update-ui-settings (read-string (get-item "bgg-ui-settings"))]))
  (re-frame/dispatch [::network-events/update-result])

  ;; auth is not ready
  (on-auth-state-changed (fn [x]
                           (re-frame/dispatch [::login-events/auth-state-changed])
                           (on-auth-state-changed-callback x)))
  (dev-setup)
  (mount-root))

(comment
  (remove-item! "bgg-ui-settings")
  (init))
