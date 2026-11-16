#!/bin/bash
cd "$(dirname "$0")/.."
java -cp "target/meshtalk-1.0.0.jar:target/classes:$HOME/.m2/repository/org/openjfx/javafx-controls/21.0.2/*" meshtalk.client.ClientCli "$@"
