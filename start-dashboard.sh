#!/bin/bash

# index the files to speed up display
mvn exec:java -Dexec.mainClass="cessda.cmv.benchmark.GenerateManifest"

# start the webserver
npx serve . 