(ns thesis.content-script.image
  (:require-macros [hiccups.core :as hiccups :refer [html]]
                   [cljs.core.async.macros :as m :refer [go go-loop]])
  (:require [chromex.logging :refer-macros [log]]
            [domina.css :refer [sel]]
            [cljs.core.async :refer [chan close! <! >!]]
            [domina.core :refer [by-id value set-value! destroy! append! by-class add-class!]]))

(def THREE js/THREE)

(defn init!
  [img]
  (let [w (.-innerWidth js/window)
        h (.-innerHeight js/window)
        camera (THREE.PerspectiveCamera. 40 (/ w h) 1 10000)
        scene (THREE.Scene.)
        renderer (THREE.CSS3DRenderer.)
        div (.. js/document (createElement "div"))
        object (THREE.CSS3DObject. div)
        chan (chan)]

    (set! (.-z (.-position camera)) 3000)
    (set! (.-backgroundImage (.-style div)) (str "url(" img ")"))
    (set! (.-width (.-style div)) (str w "px"))
    (set! (.-height (.-style div)) (str h "px"))

    (set! (.-x (.-rotation object)) 0)
    (set! (.-y (.-rotation object)) 0)
    (set! (.-z (.-rotation object)) 0)

    (set! (.-x (.-position object)) 0)
    (set! (.-y (.-position object)) 0)
    (set! (.-z (.-position object)) 0)

    (.setSize renderer w h)
    (add-class! (.-domElement renderer) "renderer")
    (set! (.-position (.-style (.-domElement renderer))) "absolute")
    (.. scene (add object))
    (append! (sel "body") (.-domElement renderer))

    (go (loop [i 0]
          (js/requestAnimationFrame #(go (.render renderer scene camera)
                                          (set! (.-y (.-rotation object)) (/ i 100))
                                          (set! (.-y (.-position object)) (/ i 10))
                                         (>! chan (+ i 1))))
          (recur (<! chan))))))