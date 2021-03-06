(ns bbg-reframe.components.bottom-buttons-bar-comp
  (:require
   [re-frame.core :as re-frame]
   [bbg-reframe.subs :as subs]
   [bbg-reframe.events :as events]
   [bbg-reframe.components.button-comp :refer [button-comp]]
   [bbg-reframe.components.bottom-overlay-comp :refer [bottom-overlay-comp]]))

(defn bottom-buttons-bar-comp []
  (let [open-tab @(re-frame/subscribe [::subs/ui :open-tab])]
    [:div.bottom-overlay-box-shadow.pr-2.p-1.z-10.flex.flex-col
     (bottom-overlay-comp)
     [:div.flex.gap-2
      [button-comp {:style {:flex-grow "4"}
                    :active (= open-tab :sliders-tab)
                    :on-click #(re-frame/dispatch [::events/set-open-tab :sliders-tab])
                    :children [:i.mx-auto.my-auto {:class "fa-solid fa-sliders fa-xl"}]}]
      [button-comp {:style {:flex-grow "4"}
                    :active (= open-tab :sort-tab)
                    :on-click #(re-frame/dispatch [::events/set-open-tab :sort-tab])
                    :children [:i.mx-auto.my-auto {:class "fa-solid fa-sort fa-xl"}]}]
      [button-comp {:active (= open-tab :user-name-tab)
                    :on-click #(re-frame/dispatch [::events/set-open-tab :user-name-tab])
                    :children [:i.mx-auto.my-auto {:class "fa-solid fa-user fa-xl"}]}]]]))