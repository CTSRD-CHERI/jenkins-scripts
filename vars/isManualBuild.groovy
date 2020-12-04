def call() {
    def manualBuild = false
    currentBuild.buildCauses.eachWithIndex { item, index ->
        echo "Build cause ${index}: ${item} -- class=${item._class}"
        if ("${item._class}" == 'hudson.model.Cause$UserIdCause')
            manualBuild = true
    }
    return manualBuild
}
