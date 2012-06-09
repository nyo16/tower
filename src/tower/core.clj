(ns tower.core
  ;; TODO
  "Simple internationalization (i18n) library for Clojure. Wraps standard Java
  functionality when possible, doing away with unnecessary boilerplate."
  {:author "Peter Taoussanis"}
  (:require [clojure.string :as str]
            [tower.utils :as utils])
  (:import (java.text NumberFormat DateFormat)))

(defn locale
  "Creates a Java Locale object with a lowercase ISO-639 language code,
  optional uppercase ISO-3166 country code, and optional vender-specific variant
  code. With no args, returns JVM's default Locale."
  ([]                     (java.util.Locale/getDefault))
  ([lang]                 (java.util.Locale. lang))
  ([lang country]         (java.util.Locale. lang country))
  ([lang country variant] (java.util.Locale. lang country variant)))

(defn parse-locale
  "Creates Locale from name string or keyword: :en, :en_US, :en_US_variant, etc.
  Return JVM's default Locale for nil or blank string."
  [locale-name]
  (if (or (nil? locale-name) (str/blank? (name locale-name)))
    (locale)
    (apply locale (str/split (name locale-name) #"_"))))

(comment (parse-locale :en_US)
         (parse-locale nil))

;;; Thread-local working vars
(declare ^:dynamic *locale*
         ^:dynamic *translations-db*
         ^:dynamic *translation-scope*
         ^:dynamic *dev-mode?*)

(defmacro with-i18n
  "Executes body after setting appropriate thread-local i18n vars. locale should
  be a java.util.Locale object. @translations-db should be a prepared map of
  form {locale-name {scoped-translation-key \"translated text\"}}.
  translation-scope is of form :ns1/.../nsN or nil.

  See (map->translations-db) for more information about translations-db."
  [locale translations-db translation-scope dev-mode? & body]
  `(binding [*locale*             ~locale
             *translations-db*    ~translations-db
             *translation-scope*  ~translation-scope
             *dev-mode?*          ~dev-mode?]
     ~@body))

(defmacro ^:private !with-i18n
  "Debug form of 'with-i18n'."
  [locale & body]
  `(with-i18n ~locale
     (atom {:en        {:a/b/c "English text"
                        :e/f   "Different English text"}
            :en_US     {:a/b/c "English (US) text"}
            :en_UK     {:a/b/c "English (UK) text"}
            :en_UK_va1 {:a/b/c "English (UK, var1) text"}})
     nil true
     ~@body))

(defmacro with-scope
  "Executes body with given translation scope :ns1/.../nsN or nil."
  [translation-scope & body]
  `(binding [*translation-scope* ~translation-scope]
     ~@body))

(comment
  (!with-i18n (locale) (t :a/b/c))
  (!with-i18n (locale) (with-scope :a/b (t :c))))

;;;; Collation, etc.

(def ^:private get-collator "Memoized collator"
  (memoize (fn [locale] (java.text.Collator/getInstance locale))))

(defn u-compare "Localized Unicode comparator."
  [x y] (.compare ^java.text.Collator (get-collator *locale*) x y))

(comment
  (!with-i18n (locale) (sort u-compare ["a" "d" "c" "b" "f" "_"])))

(defn normalize
  "Transforms Unicode string into W3C-recommended standard de/composition form
  allowing easier searching and sorting of strings. Normalization is considered
  good hygiene when communicating with a DB or other software."
  [s] (java.text.Normalizer/normalize s java.text.Normalizer$Form/NFC))

;;;; Localized number formatting
;;; Memoized formatters

(def ^:private f-number
  (memoize (fn [locale] (NumberFormat/getNumberInstance locale))))

(def ^:private f-integer
  (memoize (fn [locale] (NumberFormat/getIntegerInstance locale))))

(def ^:private f-percent
  (memoize (fn [locale] (NumberFormat/getPercentInstance locale))))

(def ^:private f-currency
  (memoize (fn [locale] (NumberFormat/getCurrencyInstance locale))))

;;; Utility fns

(defn format-number   [x] (.format ^NumberFormat (f-number   *locale*) x))
(defn format-integer  [x] (.format ^NumberFormat (f-integer  *locale*) x))
(defn format-percent  [x] (.format ^NumberFormat (f-percent  *locale*) x))
(defn format-currency [x] (.format ^NumberFormat (f-currency *locale*) x))

(defn parse-number    [s] (.parse ^NumberFormat  (f-number   *locale*) s))
(defn parse-integer   [s] (.parse ^NumberFormat  (f-integer  *locale*) s))
(defn parse-percent   [s] (.parse ^NumberFormat  (f-percent  *locale*) s))
(defn parse-currency  [s] (.parse ^NumberFormat  (f-currency *locale*) s))

(comment
  (!with-i18n (locale "en" "ZA") (format-currency 200))
  (!with-i18n (locale "en" "ZA") (parse-currency "R 200.33")))

;;;; Localized date/time formatting
;;; Memoized formatters

(def ^:private f-date
  (memoize (fn [style locale] (DateFormat/getDateInstance style locale))))

(def ^:private f-time
  (memoize (fn [style locale] (DateFormat/getTimeInstance style locale))))

(def ^:private f-dt
  (memoize (fn [date-style time-style locale]
             (DateFormat/getDateTimeInstance date-style time-style locale))))

;;; Utility fns

(defn style
  "Returns a DateFormat time/date style constant by style key e/o #{:short
  :medium :long :full}."
  [style]
  (get {:short  DateFormat/SHORT
        :medium DateFormat/MEDIUM
        :long   DateFormat/LONG
        :full   DateFormat/FULL} style DateFormat/DEFAULT))

(defn format-date [style d] (.format ^DateFormat (f-date style *locale*) d))
(defn format-time [style t] (.format ^DateFormat (f-time style *locale*) t))
(defn format-dt   [date-style time-style dt]
  (.format ^DateFormat (f-dt date-style time-style *locale*) dt))

(defn parse-date [style s] (.parse ^DateFormat (f-date style *locale*) s))
(defn parse-time [style s] (.parse ^DateFormat (f-time style *locale*) s))
(defn parse-dt   [date-style time-style s]
  (.parse ^DateFormat (f-dt date-style time-style *locale*) s))

(comment
  (!with-i18n (locale "en" "ZA") (format-date (style :full) (java.util.Date.)))
  (!with-i18n (locale "en" "ZA") (format-time (style :short) (java.util.Date.)))
  (!with-i18n (locale "en" "ZA") (format-dt (style :full) (style :full)
                                            (java.util.Date.))))

;;;; Localized text formatting

(defn format-str
  "Like clojure.core/format, but uses a locale."
  ^String [fmt & args]
  (String/format *locale* fmt (to-array args)))

(defn format-msg
  "Creates a localized message formatter and parse pattern string, substituting
  given arguments as per MessageFormat spec."
  [pattern & args]
  (let [formatter     (java.text.MessageFormat. pattern *locale*)
        string-buffer (.format formatter (to-array args) (StringBuffer.) nil)]
    (.toString string-buffer)))

(comment
  (!with-i18n (locale) (format-msg "foobar {0}!" 102.22))
  (!with-i18n (locale) (format-msg "foobar {0,number,integer}!" 102.22))
  (!with-i18n (locale)
    (format-msg "You have {0,choice,0#no cats|1#one cat|1<{0,number} cats}." 0)))

;;;; Translation-db management

(defn- compile-map-path
  "[locale-name :ns1 ... :nsM unscoped-key.decorator translation] =>
  {locale-name {:ns1/.../nsM/unscoped-key (f translation decorator)}}"
  [path]
  {:pre [(seq path) (>= (count path) 3)]}
  (let [path        (vec path)
        locale-name (first path)
        translation (peek path)
        scope-ks    (subvec path 1 (- (count path) 2)) ; [:ns1 ... :nsM]

        ;; Check for possible decorator
        [unscoped-k decorator] (-> (name (peek (pop path)))
                                   (str/split #"[\._]"))

        ;; [:ns1 ... :nsM :unscoped-key] => :ns1/.../nsM/unscoped-key
        scoped-key (->> (conj scope-ks unscoped-k)
                        (map name)
                        (str/join "/")
                        (keyword))]

    (when (not= decorator :note) ; Notes get discarded
      {locale-name
       {scoped-key
        (case decorator
          :html translation ; HTML-safe, leave unchanged

          ;; Inline markdown (& escape)
          :md   (-> (str translation)
                    (utils/escape-html)
                    (utils/inline-markdown->html))

          ;; No special decorator (just escape)
          (utils/escape-html translation))}})))

(comment (compile-map-path [:en_US :a :b :c :d.md "*d-data*"])
         (compile-map-path [:en_US :a "*d-data*"]))

(defn map->translations-db
  "Processes text translations stored in a simple development-friendly Clojure
  map into form required by localized text translator.

  {:en    {:root {:buttons {:login.html \"<strong>Sign in</strong>\"
                            :login.note \"Title of login button (bold)\"
                            :logout.md  \"**Sign out**\"
                            :message    \"Hello & welcome.\"}}}
   :en_US {:root {:buttons {:logout     \"American sign out\"}}}}
  =>
  {:canonical-locale canonical-locale
   :en    {:root/buttons/login   \"<strong>Sign in</strong>\"
           :root/buttons/logout  \"<strong>Sign out</strong>\"
           :root/buttons/message \"Hello &amp; welcome.\"}
   :en_US {:root/buttons/logout  \"American sign out\"}}

  Note the optional key decorators."
  [canonical-locale m]
  (->> (utils/leaf-paths m)
       (map compile-map-path)
       (apply merge-with merge ; 1-level recursive merge
              {:canonical-locale canonical-locale})))

(comment
  (map->translations-db
   :en
   {:en    {:root {:buttons {:login.html "<strong>Sign in</strong>"
                             :login.note "Title of login button (bold)"
                             :logout.md  "**Sign out**"
                             :message    "Hello & welcome."}}}
    :en_US {:root {:buttons {:logout     "American sign out"}}}}))

(defn map->xliff [m]) ; TODO Use hiccup?
(defn xliff->map [s]) ; TODO Use clojure.xml/parse?

;;;; Translation

(defn- fqname
  "Like 'name' but nil-safe and returns fully-qualified name String."
  [keyword]
  (when keyword
    (if-let [ns (namespace keyword)]
      (str ns "/" (name keyword))
      (name keyword))))

(comment (fqname :a/b/c/d))

(def ^:private locales-to-check
  "Returns vector of locale names to check, in order of preference:
  #<Locale en_US_var1> => [:en_US_var1 :en_US :en]
  #<Locale en_US>      => [:en_US :en]
  #<Locale en>         => [:en]"
  (memoize
   (fn [locale]
     (let [parts (str/split (str locale) #"_")]
       (vec (for [n (range (count parts) 0 -1)]
              (keyword (str/join "_" (take n parts)))))))))

(comment (locales-to-check (locale)))

(defn t
  "Localized text translator. Takes a namespaced key :ns1/.../nsM within a scope
  :nsA/.../nsN and returns the best translation available within working locale.

  With additional arguments, treats translated text as pattern for message
  formatting.

  In production mode, missing translations will fall back to canonical
  translation or to \"\" if that's missing too."
  ([scoped-key & args] (apply format-msg (t scoped-key) args))
  ([scoped-key]
     (let [fully-scoped-key ; :nsa/.../nsN/ns1/.../nsM
           (if *translation-scope* (keyword (str (fqname *translation-scope*)
                                                 "/" (fqname scoped-key)))
               scoped-key)

           [lchoice1 lchoice2 lchoice3 :as lchoices] (locales-to-check *locale*)
           translations                              @*translations-db*]

       (or (get-in translations [lchoice1 fully-scoped-key])
           (when lchoice2 (get-in translations [lchoice2 fully-scoped-key]))
           (when lchoice3 (get-in translations [lchoice3 fully-scoped-key]))

           (if *dev-mode?* (str "**" fully-scoped-key "**")
               ;; TODO Don't want to depend on Timbre!
               (do #_(error "Missing translation"
                          (str fully-scoped-key " for " *locale*))
                   (or (get-in translations [(:canonical-locale translations)
                                             fully-scoped-key]
                               ""))))))))

(comment
  (!with-i18n (locale) (t :not/a/real/key))
  (!with-i18n (locale) (t :a/b/c))
  (!with-i18n (locale) (t :e/f))
  (!with-i18n (locale) (t :a/b/c/d)))