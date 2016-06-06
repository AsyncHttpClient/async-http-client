#!/bin/bash
ulimit -H -n 4000
if ([ $TRAVIS_PULL_REQUEST = "false" ] && [ $TRAVIS_BRANCH = "master" ]); then
    ./travis/make_credentials.py
fi
