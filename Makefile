.PHONY: test deploy clean

VERSION ?= 1.11

clean:
	lein clean

test: clean
	lein with-profile -user,-dev,+$(VERSION) test

test-cljs: clean
	lein with-profile -user,-dev,+cljsbuild cljsbuild once
	node target/cljs/test.js

cljfmt:
	lein with-profile -user,+$(VERSION),+cljfmt cljfmt check

cljfmt-fix:
	lein with-profile -user,+$(VERSION),+cljfmt cljfmt fix

eastwood:
	lein with-profile -user,+$(VERSION),+deploy,+eastwood eastwood

kondo:
	lein with-profile -dev,+$(VERSION),+clj-kondo run -m clj-kondo.main --lint src test .circleci/deploy


# Deployment is performed via CI by creating a git tag prefixed with "v".
# Please do not deploy locally as it skips various measures.
deploy: check-env
	lein with-profile -user,-dev,+$(VERSION) deploy clojars

# Usage: PROJECT_VERSION=0.3.0 make install
# PROJECT_VERSION is needed because it's not computed dynamically.
install: check-install-env
	lein with-profile -user,-dev,+$(VERSION) install

check-env:
ifndef CLOJARS_USERNAME
	$(error CLOJARS_USERNAME is undefined)
endif
ifndef CLOJARS_PASSWORD
	$(error CLOJARS_PASSWORD is undefined)
endif
ifndef CIRCLE_TAG
	$(error CIRCLE_TAG is undefined)
endif

check-install-env:
ifndef PROJECT_VERSION
	$(error Please set PROJECT_VERSION as an env var beforehand.)
endif
