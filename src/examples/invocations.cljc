(ns invocations
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.algorithms.v20150901-validation :as v]
    [com.fulcrologic.statecharts.chart :refer [statechart]]
    [com.fulcrologic.statecharts.elements :refer [Send data-model final invoke log on-entry on-exit state transition]]
    [com.fulcrologic.statecharts.environment :as e]
    [com.fulcrologic.statecharts.event-queue.core-async-event-loop :as loop]
    [com.fulcrologic.statecharts.simple :as simple]
    [taoensso.timbre :as log]))

(def child-chart
  (statechart {}
    (data-model {:x 1})
    (state {}                                               ; top-level state so we can log an exit from the overall machine
      (on-exit {}
        (log {:label "CHILD CHART EXIT"}))

      (transition {:event  :event/exit
                   :target :state/final})

      (state {:id :X}
        (on-entry {}
          (log {:label "Child X" :expr (fn [_ {:keys [x]}] x)}))
        ;; NOTE: The future invocation processor is only coded for CLJ, so this invocation is ignored in CLJS
        (invoke {:id     :child/future
                 :type   :future
                 :params {:time (fn [_ {:keys [x]}] x)}
                 :src    (fn [{:keys [time]}]
                           (log/info "Invocation running for" time)
                           #?(:clj (try
                                     (Thread/sleep (* 1000 time))
                                     (catch InterruptedException e
                                       (log/info "Future cancelled!")
                                       (throw e))))
                           (log/info "Future function completed")
                           {:future-result :DONE})})
        (transition {:event :done.invoke}
          (log {:label "Future indicated that it completed"
                :expr  (fn [e d]
                         (log/spy :debug d))}))
        (transition {:event :child/swap :target :Y}))
      (state {:id :Y}
        (on-entry {}
          (log {:label "Child Y"}))
        (on-exit {}
          (Send {:event      :child.left-y
                 :type       ::sc/chart
                 :targetexpr (fn [env data]
                               (e/parent-session-id env))}))
        (transition {:event :child/swap :target :X})))
    (final {:id :state/final})))

(comment
  (v/problems child-chart))

(def main-chart
  (statechart {}
    (state {:id :A}
      (on-entry {}
        (log {:label "Main A"}))
      (transition {:event :swap :target :B}))
    (state {:id :B}
      (on-entry {}
        (log {:label "Main B"}))
      (transition {:event :swap :target :A})
      (transition {:event :child.*}
        (log {:label "Child event" :expr (fn [_ data] data)}))
      (invoke {:id          :main/child
               :autoforward true                            ; be careful, sends all events through, but it is possible to cause infinite loops if child sends events in response
               :type        :statechart
               :src         `child-chart
               :params      {:x 10}
               :finalize    (fn [env data]
                              (log/info "Finalize: " data))}))))

(comment
  (do
    (log/set-level! :debug)                                 ; so we can see log elements
    (def session-id 42)
    (def env (simple/simple-env))
    (simple/register! env `main-chart main-chart)
    (simple/register! env `child-chart child-chart)
    (def running? (loop/run-event-loop! env 100))
    (simple/start! env `main-chart session-id))

  ;; Sending this multiple times with swap main chart states. When in B, a child chart will start.
  (simple/send! env {:target session-id
                     :event  :swap})

  ;; With autoforard on, we can ferry events though to the child
  (simple/send! env {:target session-id
                     :event  :child/swap})

  ;; When the child chart is running (top chart is in :B), can send it events
  ;; The session ID of a child session will be the string of <parent-session-ID `.` <id | invokeid>>
  (simple/send! env {:target "42.:main/child"
                     :event  :child/swap})

  (simple/send! env {:target "42.:main/child"
                     :event  :event/exit})

  ;; terminate the event loop
  (reset! running? false))
