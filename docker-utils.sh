 #!/usr/bin/env bash

 set -e

 GRADLE_CACHE_ARCHIVE='build-cache/maven.tar.gz'
 GRADLE_CACHE_DIR="/root/.m2/"

 function __load_cache {
   if [ -f "$GRADLE_CACHE_ARCHIVE" ]
   then
    mkdir $GRADLE_CACHE_DIR
    tar -xzf $GRADLE_CACHE_ARCHIVE -C $GRADLE_CACHE_DIR
    echo "Extracted $GRADLE_CACHE_ARCHIVE to $GRADLE_CACHE_DIR"
   else
     echo "$GRADLE_CACHE_ARCHIVE not found"
   fi
 }

 function __save_cache {
   # gzip without timestamps for consistent checksum
   GZIP=-n tar -czf $GRADLE_CACHE_ARCHIVE --exclude='./daemon' --exclude='./native' --exclude='./notifications' -C $GRADLE_CACHE_DIR .
   md5sum $GRADLE_CACHE_ARCHIVE
   echo "Saved $GRADLE_CACHE_DIR to $GRADLE_CACHE_ARCHIVE"
 }

 case "$1" in
   load-cache)
     __load_cache
     ;;
   save-cache)
     __save_cache
     ;;
   *)
     echo 'Unknown command'
     exit 1
 esac

