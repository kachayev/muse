#!/bin/bash

# FIXME: Explicit namespace are needed because Leiningen ignores .cljc files :_
#  https://github.com/technomancy/leiningen/issues/1827
TEST_NAMESPACES=(
    muse.test.core
    muse.test.cats
)

lein test ${TEST_NAMESPACES[@]}
