#!/bin/bash
ulimit -u 514029
ulimit -a
if ([ $TRAVIS_PULL_REQUEST = "false" ] && [ $TRAVIS_BRANCH = "master" ]); then
    ./travis/make_credentials.py
fi
