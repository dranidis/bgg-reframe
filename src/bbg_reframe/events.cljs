(ns bbg-reframe.events
  (:require
   [re-frame.core :as re-frame]
   [day8.re-frame.tracing :refer-macros [fn-traced]]
   [ajax.core :as ajax]
   [bbg-reframe.model.sort-filter :refer [sorting-fun rating-higher-than? with-number-of-players? and-filters is-playable-with-num-of-players playingtime-between? game-more-playable?]]
   [clojure.tools.reader.edn :refer [read-string]]
   [bbg-reframe.model.db :refer [game-id collection-game->game game-votes]]
   [tubax.core :refer [xml->clj]]
   [bbg-reframe.model.localstorage :refer [set-item!]]
   [clojure.string :refer [split]]
   [re-frame.loggers :refer [console]]))


(def delay-between-fetches 100)
(def cors-server-uri "https://guarded-wildwood-02993.herokuapp.com/")

(re-frame/reg-event-db
 ::initialize-db
 (fn-traced [_ _]
            ;; db/default-db
            {:result nil
             :fields ["name"]
             :form {:sort-id "playable"
                    :take "10"
                    :higher-than "7.0"
                    :players "4"
                    :threshold "0.8"
                    :time-limit "180"}
             :games []
             :queue #{}
             :fetching #{}
             :fetches 0
             :error nil
             :cors-running false
             :user nil
             :ui {:sort-by-button-state false}}))

;; (re-frame/reg-event-db
;;  ::field
;;  (fn-traced[db [_ field e]]
;;    (let [_ (println e field)
;;          new-fields
;;          (if (some #(= field %) (:fields db))
;;            (filter #(not= field %) (:fields db))
;;            (conj (:fields db) field))]
;;      (assoc db :fields new-fields))))

(re-frame/reg-event-fx
 ::update-result
;;  [(when debug? re-frame.core/debug)]
 (fn-traced [{:keys [db]} _]
            (let [;; _ (console :debug "update")
                  sort-by  (get-in db [:form :sort-id])
                  sorting-fun (if (= sort-by "playable")
                                (game-more-playable? (read-string (get-in db [:form :players])))
                                (get sorting-fun (keyword (get-in db [:form :sort-id]))))
                  result (take (read-string (get-in db [:form :take]))
                               (sort sorting-fun
                                     (filter
                                      (and-filters
                                       (with-number-of-players?
                                         (read-string (get-in db [:form :players])))
                                       (rating-higher-than?
                                        (read-string (get-in db [:form :higher-than])))
                                       (playingtime-between?
                                        0 (read-string (get-in db [:form :time-limit])))
                                       (is-playable-with-num-of-players
                                        (get-in db [:form :players])
                                        (get-in db [:form :threshold])))
                                      (vals (get db :games)))))]
              {:db (assoc db :result result)
               :dispatch [::update-queue (map :id result)]})))

(re-frame/reg-event-fx
 ::update-form
 (fn-traced [{:keys [db]} [_ id val]]
            {:db (assoc-in db [:form id] val)
             :dispatch [::update-result]}))

(re-frame/reg-event-fx
 ::update-user
 (fn-traced [{:keys [db]} [_ val]]
            (let [_ (set-item! "bgg-user" val)]
              {:db (assoc db :user val)})))

(re-frame/reg-event-fx
 ::update-games
 (fn-traced [{:keys [db]} [_ val]]
            {:db (assoc db :games val)}))


(re-frame/reg-event-fx                             ;; note the trailing -fx
 ::fetch-collection                      ;; usage:  (dispatch [:handler-with-http])
 (fn-traced [{:keys [db] {:keys [cors-running user]} :db} [_ _]]                    ;; the first param will be "world"
            (if cors-running
              {:db   (assoc db
                            :loading true
                            :error nil)
               :http-xhrio {:method          :get
                            :uri             (str cors-server-uri "https://boardgamegeek.com/xmlapi/collection/" user)
                            :timeout         8000                                           ;; optional see API docs
                            :response-format (ajax/text-response-format)  ;; IMPORTANT!: You must provide this.
                            :on-success      [::success-fetch-collection]
                            :on-failure      [::bad-http-collection]}}
              {:db (assoc db
                          :error "CORS server not responding. Trying again in 2 seconds")
               :dispatch [::cors-check]
               :dispatch-later {:ms 2000
                                :dispatch [::fetch-collection user]}})))


(re-frame/reg-event-fx                             ;; note the trailing -fx
 ::fetch-game                      ;; usage:  (dispatch [:handler-with-http])
 (fn-traced [{:keys [db] {:keys [cors-running]} :db} [_ game-id]]                    ;; the first param will be "world"
            (if cors-running
              {;;  :db   (assoc db :loading true)
               :http-xhrio {:method          :get
                            :uri             (str cors-server-uri "https://boardgamegeek.com/xmlapi/boardgame/" game-id)
                            :timeout         8000                                           ;; optional see API docs
                            :response-format (ajax/text-response-format)  ;; IMPORTANT!: You must provide this.
                            :on-success      [::success-fetch-game]
                            :on-failure      [::bad-http-game]}}
              {:db (assoc db
                          :error "CORS server not responding. Trying again in 2 seconds")
               :dispatch [::cors-check]
               :dispatch-later {:ms 2000
                                :dispatch [::fetch-game game-id]}})))



;; BAD REQUEST
;; core.cljs:200 Response:  
;; {:response "<error>
;;     <message>Rate limit exceeded.</message>                        
;;                           </error>", 
;;  :last-method GET, 
;;                         :last-error Too Many Requests [429], 
;;                           :failure 
;;                           :error, 
;;                           :status-text Too Many Requests, 
;;                           :status 429, 
;;                           :uri https://guarded-wildwood-02993.herokuapp.com/https://boardgamegeek.com/xmlapi/collection/ddmits, 
;;                           :debug-message Http response at 400 or 500 level, 
;;                           :last-error-code 6}

;; 
;; 
;; Checking CORS server
;; 
(re-frame/reg-event-fx                             ;; note the trailing -fx
 ::cors-check                      ;; usage:  (dispatch [:handler-with-http])
 (fn-traced [{:keys [db]} _]                    ;; the first param will be "world"
            {:db   (assoc db :cors-running false)   ;; causes the twirly-waiting-dialog to show??
             :http-xhrio {:method          :get
                          :uri             cors-server-uri
                          :timeout         8000                                           ;; optional see API docs
                          :response-format (ajax/text-response-format)  ;; IMPORTANT!: You must provide this.
                          :on-success      [::success-cors]
                          :on-failure      [::bad-cors]}}))

(re-frame/reg-event-fx
 ::success-cors
 (fn-traced [{:keys [db]} _]
            (console :debug (str "CORS server at " cors-server-uri " up!"))
            {:db (assoc db :cors-running true)}))

;; status 0 Request failed. <-- (no network)
;; status -1 Request timed out. <-- Wrong address
(re-frame/reg-event-fx
 ::bad-cors
 (fn-traced [{:keys [db]} [_ response]]
            (console :error "CORS server down")
            (console :debug "status: " (:status response))
            (console :debug "status-text: " (:status-text response))
            {:db (assoc db :error "CORS server down")}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; 

;; <?xml version= "1.0" encoding= "utf-8" standalone= "yes" ?>
;;   <errors>
;;     <error>
;;       <message>Invalid username specified</message>
;;     </error>
;;   </errors>

(defn- error? [collection]
  (-> collection
      first
      :tag
      (= :error)))

(comment
  ;;   <?xml version= \"1.0\" encoding= \"utf-8\" standalone= \"yes\" ?>
  ;; <errors>
  ;;   <error>
  ;;     <message>Invalid username specified</message>
  ;;   </error>
  ;; </errors>


  (def xml-clj
    [{:tag :error, :attrs nil,
      :content [{:tag :message, :attrs nil, :content ["Invalid username specified"]}]}])
  xml-clj
  ;
  )

(re-frame/reg-event-fx
 ::success-fetch-collection
 (fn-traced [{:keys [db]} [_ response]]
            (let [collection (:content (xml->clj response))]
              (if (error? collection)
                (let [_ (console :debug (str "ERROR: " collection))]
                  {:db (assoc db
                              :error (str "Error reading collection. Invalid user?")
                              :loading false)})
                (let [_ (console :debug "SUCCESS: collection fetched ")
                      games (map collection-game->game collection)
                      indexed-games (reduce
                                     #(assoc %1 (:id %2) %2)
                                     {} games)
                      _ (set-item! "bgg-games" indexed-games)
                      collection-to-be-fetched collection
                      _ (console :debug (count collection-to-be-fetched))]
                  {:dispatch [::update-result]
                   :db (assoc db
                              :games indexed-games
                              :loading false)})))))

(defn fetched-games-ids
  [games]
  (into #{} (->> (vals games)
                 (remove #(nil? (:votes %)))
                 (map :id))))

(re-frame/reg-event-fx
 ::update-queue
;;  for some strange reason fn-traced does not compile
 (fn [{:keys [db] {:keys [queue fetching games]} :db} [_ results]]
   (let [new-to-fetch (->> results
                           (remove #((fetched-games-ids games) %))
                           (remove #(queue %))
                           (remove #(fetching %)))]
     {:db (assoc db
                 :queue (reduce #(conj %1 %2) queue new-to-fetch))
      :dispatch [::fetch-next-from-queue]})))

(re-frame/reg-event-fx
 ::success-fetch-game
 (fn-traced [{:keys [db] {:keys [fetches fetching loading]} :db} [_ response]]
            (let [game-received (->> response
                                     xml->clj
                                     :content
                                     first)
                  game-id (game-id game-received)
                  _  (console :debug "SUCCESS" game-id)
                  new-db (assoc-in db [:games game-id :votes] (game-votes game-received))
                  _ (set-item! "bgg-games" (:games new-db))]
              {:db (assoc new-db
                          :error nil
                          :fetching (disj fetching game-id)
                          :fetches (inc fetches))
               :fx (if loading
                     [[:dispatch [::update-result]]
                    ;; [:dispatch [::fetch-next-from-queue]]
                      ]
                     [[:dispatch [::fetch-next-from-queue]]])})))

(re-frame/reg-event-fx
 ::bad-http-collection
 (fn-traced [{:keys [db]} [_ response]]
            (console :debug "FAILURE: " response)
            (cond
              (= 0 (:status response)) {:db (assoc db
                                                   :queue #{}
                                                   :fetching #{}
                                                   :error "CORS server is not responding"
                                                   :loading false
                                                   :cors-running false)}
              :else {:db (assoc db
                                :queue #{}
                                :fetching #{}
                                :error (:status-text response)
                                :loading false)})))


(re-frame/reg-event-fx
 ::bad-http-game
 (fn-traced
  [{:keys [db] {:keys [queue fetching]} :db} [_ response]]
  (console :debug "BAD REQUEST")
  (console :debug "Response: " response)
  (cond (= 0 (:status response))
        {:db (assoc db
                    :queue #{}
                    :fetching #{}
                    :error "CORS server is not responding"
                    :loading false
                    :cors-running false)}
        (#{500 503} (:status response))
        ;; BGG throttles the requests now, which is to say that if you send requests too frequently, 
        ;; the server will give you 500 or 503 return codes, reporting that it is too busy.
        (let [uri (:uri response)
              game-id (last (split uri \/))
              _ (console :debug (str (:status-text response) " Puting " game-id " back in the queue"))]
          {:db {assoc db
                :queue (conj queue game-id)
                :fetching (disj fetching game-id)}
           :dispatch-later {:ms 3000
                            :dispatch [::fetch-next-from-queue]}})
        (= 404 (:status response))
        {:db (assoc db
                    :queue #{}
                    :fetching #{}
                    :error (str "Game not found or bad address!")
                    :loading false)
         :dispatch [::fetch-next-from-queue]}
        :else
        {:db (assoc db
                    :queue #{}
                    :fetching #{}
                    :error "Problem with BGG??"
                    :loading false)
         :dispatch [::fetch-next-from-queue]})))


(defn fetch-next-from-queue-handler [{:keys [db] {:keys [queue fetching games]} :db} _]
  (if (and (empty? queue) (seq fetching)) ;; non-empty fetching
    ;; nothing to do; there are still games being fetched
    {}
    ;; queue is not empty
    ;; or
    ;; fetching is empty
    ;;
    ;; CASES:
    ;;   queue not empty ; fetching not empty
    ;;   queue not empty ; fetching empty
    ;;   queue empty     ; fetching empty
    (let [fetch-now (if (empty? fetching)
                      (first (->> (keys games)
                                  (remove #((fetched-games-ids games) %))
                                  (remove #(queue %))
                                  (remove #(fetching %)))) ;; will be nil if all fetched
                      (first queue)) ;; if fetching not empty, queue is not empty
          ]
      (when fetch-now
        (console :debug
                 (if (empty? fetching)
                   "New to fetch (background): "
                   "fetch-next-from-queue: fetching ")
                 fetch-now))
      (merge
       {:db (assoc db
                   :queue (disj queue fetch-now)
                   :fetching (if fetch-now
                               (conj fetching fetch-now)
                               fetching)
                   :loading (> (count fetching) 0))}
       (if fetch-now
         {:dispatch-later
          {:ms (* (inc (count fetching)) delay-between-fetches)
           :dispatch [::fetch-game fetch-now]}}
         {})))))

(re-frame/reg-event-fx
 ::fetch-next-from-queue
 fetch-next-from-queue-handler)


(re-frame/reg-event-db
 ::toggle-sort-by-button-state
 (fn [db]
   (assoc-in db [:ui :sort-by-button-state] (not (get-in db [:ui :sort-by-button-state])))))
