#!/bin/bash
if ([ $TRAVIS_PULL_REQUEST = "false" ] && [ $TRAVIS_BRANCH = "master" ]); then
    ./mvnw deploy
fi
