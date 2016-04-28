(ns torpatches.core
  "Program to generate the torpat.ch website. URLs like
   https://torpat.ch/5856 redirect or link to diff(s) on
   https://gitweb.torproject.org/ ."
  {:author "Arthur Edelstein"}
  (:require [clojure.java.shell :as shell]
            [clojure.string :as string]
            [hiccup.page :as page]))

(defn match
  "Use a regular expression to find the first matching
   item (use parentheses)."
  [re s]
  (some-> (re-find re s) second))

(defn shell-lines
  "Send the line to the shell, and return a sequence
   of lines from the resulting process's output."
  [line & etc]
  (->> (apply shell/sh
              (concat (clojure.string/split line #" ")
                      etc))
       :out
       string/split-lines
       (map string/trim)))

(defn branches
  "List names of git branches."
  [dir]
  (shell-lines "git branch -a" :dir dir))

(defn fetch-latest-branches! []
  "Download the latest tor-browser branches from git.torproject.org."
  (shell-lines "git fetch origin" :dir "../tor-browser"))

(defn newest-tor-browser-branch
  "Get the name of the most recent Tor Browser branch.
   Assumes branches are named by semantic versioning."
  []
  (->> (branches "../tor-browser")
       (filter #(.startsWith % "remotes/origin/tor-browser-"))
       sort
       ;(sort-by #(match #"esr\-([^x]*+)" %))
       last))

(defn latest-commits
  "Get the latest n patches for the given branch."
  [branch n]
  (->> (shell-lines (str "git log --oneline "
                         branch "~" n ".." branch)
                    :dir "../tor-browser")
       (map #(string/split % #"\s" 2))))

(defn bug-number
  "Takes a commit message and extracts the bug number."
  [commit-message]
  (or (match #"(TB\d+)" commit-message)
      (match #"[Bb]ug \#?([0-9]+)" commit-message)
      (match #"\#?([0-9]+)" commit-message)
      "None"))

(defn patch-url
  "Returns a URL for a tor-browser patch, given the hash."
  [hash]
  (str "https://gitweb.torproject.org/tor-browser.git/patch/?id=" hash))

(defn contains-any
  "Returns first item in fragments can that be found in string."
  [string fragments]
  (some #(.contains string %) fragments))

(defn remove-mozilla-commits
  "Remove mozilla commits, which are obvious from an 'r=' tag
   or similar."
  [commits]
  (remove #(let [[hash msg] %]
             (contains-any msg ["r=" "a=" "No bug,"]))
          commits))

(defn read-bugs-list
  "Retrieve a list of [commit-hash commit-message]."
  [branch]
  (->> (latest-commits branch 200)
       remove-mozilla-commits
       (remove nil?)))

(defn separate
  "Returns [coll-true coll-false], where coll-true is every
   member of coll that is true, and coll-false is every
   member of coll that is false."
  [pred coll]
  [(filter pred coll)
   (remove pred coll)])

(defn singles-and-multiples
  "Takes the bug list and returns a vector pair of two bugs maps--
   the first has bugs with single patches, the second
   bugs with multiple patches."
  [bug-list]
  (->> bug-list
       (group-by #(-> % second bug-number))
       (separate #(let [[k v] %] (= 1 (count v))))))

(defn patch-url
  "Generates a Tor Browser patch url from the patch hash."
  [hash]
  (str "https://gitweb.torproject.org/tor-browser.git/patch/?id=" hash))

(defn redirect-line
  "Takes a [trac-ticket [[hash message]]] pair and generates
   an nginx redirect line."
  [[trac-ticket [[hash _]]]]
  (str "location /" trac-ticket " { rewrite ^ " (patch-url hash) "; }\n"))

(defn now-string
  "Returns the current date-time in UTC as a reasonably readable string."
  []
  (let [date-format (java.text.SimpleDateFormat. "yyyy-MMM-dd HH:mm 'UTC'")]
    (.setTimeZone date-format (java.util.TimeZone/getTimeZone "UTC"))
    (.format date-format (java.util.Date.))))

(defn footer
  "A footer for each page."
  []
  [:p [:span {:style "font-style: italic"} "Last update: " (now-string) " "]
   [:span [:a {:href "https://github.com/arthuredelstein/torpatches"}
           "(Source on github)"]]])

(defn write-redirect-file
  "Create a redirect file from the single-patch bugs map."
  [single-patch-bugs]
  (spit
   "/etc/nginx/redirects.txt"
   (apply str (map redirect-line single-patch-bugs))))

(defn write-patch-list
  "Create an HTML page that displays a list of links to patches
   given in commits."
  [tag title commits]
  (spit
   (str "../../torpat.ch/" tag)
   (page/html5
    [:head [:title title] [:meta {:charset "utf-8"}]]
    [:body
     [:h3 title]
     [:pre
      [:ul
       (for [[hash message] commits]
         [:li hash " " [:a {:href (patch-url hash)} message]])]]
     (footer)])))

(defn write-indirect-page
  "Create an HTML page that displays a list of links to patches
   for a given Tor Browser bug."
  [[ticket commits]]
  (let [title (str "Patches for Tor Browser Bug #" ticket)]
    (write-patch-list ticket title commits)))

(defn write-isolation-page
  "Create an HTML page that displays a list of the isolation
   pages."
  [bugs-list]
  (let [isolation-commits (filter #(-> % second (contains-any ["solat" "#5742"]))
                                  bugs-list)]
    (write-patch-list "isolation"
                      "Tor Browser Isolation Patches"
                      isolation-commits)))

(defn write-index
  "Write an index.html file that is visible at https://torpat.ch .
   Shows time of last update."
  [branch]
  (spit
   "../../torpat.ch/index.html"
   (page/html5
    [:head [:title "torpat.ch"] [:meta {:charset "utf-8"}]]
    [:body
     [:h3 "torpat.ch"]
     [:div "Useful links:"
      [:ul
       [:li "Current tor-browser.git branch: "
        [:a {:href (str "https://gitweb.torproject.org/tor-browser.git/log/?h="
                        branch)} branch]]
       [:li [:a {:href "https://bugzilla.mozilla.org/buglist.cgi?quicksearch=whiteboard%3A[tor]"}
             "whiteboard:[tor] bugs on bugzilla.mozilla.org"]]
       [:li [:a {:href "/isolation"} "Isolation patches"]]]]
     (footer)
     ])))

(defn -main [& args]
  "The main program. Works out the Tor Browser trac ticket number for each
   patch. For bugs with a single patch, generates a redirect from
   https://torpat.ch/#### (where #### is the ticker number) to the patch at
   https://gitweb.torproject.org. For bugs with multiple patches,
   creates a page at https://torpat.ch/#### that links to each of those patches."
  (fetch-latest-branches!)
  (let [branch (newest-tor-browser-branch)
        bugs-list (read-bugs-list branch)
        [single-patch-bugs multi-patch-bugs] (singles-and-multiples bugs-list)]
    (write-redirect-file single-patch-bugs)
    (println "Wrote redirects file.")
    (write-isolation-page bugs-list)
    (println "Wrote isolation page.")
    (dorun (map write-indirect-page multi-patch-bugs))
    (println "Wrote multipatch link files.")
    (write-index (last (.split branch "/")))
    (println "Wrote index.")))
