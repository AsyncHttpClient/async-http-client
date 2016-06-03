#!/bin/bash
if ([ $TRAVIS_PULL_REQUEST = "false" ] && [ $TRAVIS_BRANCH = "master" ]); then
    mvn deploy
fi
