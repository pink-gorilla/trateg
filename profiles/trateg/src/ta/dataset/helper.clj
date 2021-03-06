(ns ta.dataset.helper
  (:require [tech.v3.dataset :as dataset]
            [tablecloth.api :as tablecloth]
            [tick.alpha.api :as tick]
            [fastmath.core :as math]
            [fastmath.stats :as stats]
            [tech.v3.datatype.functional :as fun]
            [tech.v3.datatype :as dtype]))

(defn days-ago [n]
  (-> (tick/now)
      (tick/- (tick/new-duration n :days))
      (tick/date)))

(defn random-dataset [n]
  (tablecloth/dataset
   {:date (->> (range n)
               (map days-ago)
               reverse)
    :close (repeatedly n rand)}))

(defn select-recent-rows [ds date]
  (-> ds
      (tablecloth/select-rows
       (fn [row]
         (-> row
             :date
             (tick/>= date))))))

(defn random-datasets [m n]
  (repeatedly m #(random-dataset n)))

  (defn standardize [xs]
    (-> xs
        (fun/- (fun/mean xs))
        (fun// (fun/standard-deviation xs))))


  (defn rand-numbers [n]
    (dtype/clone
     (dtype/make-reader :float32 n (rand))))
