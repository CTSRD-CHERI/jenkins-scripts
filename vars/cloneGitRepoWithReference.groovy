def fileOutsideWorkspaceExists(String path) {
    def returncode = sh returnStatus: true, script: "stat ${path}"
    return returncode == 0
}

def call(Map args) {
    def cheribuildSCM = [
            $class                           : 'GitSCM',
            branches                         : [[name: '*/master']],
            doGenerateSubmoduleConfigurations: false,
            submoduleCfg                     : [],
            userRemoteConfigs                : [[url: args.url]]
    ]
    if (fileOutsideWorkspaceExists('/var/tmp/git-reference-repos/cheribuild')) {
        cheribuildSCM["extensions"] = [
                [$class: 'CloneOption', depth: 0, noTags: true, reference: '/var/tmp/git-reference-repos/cheribuild', shallow: false, timeout: 5]
        ]
        echo("Using reference repo for cheribuild")
    }
    return checkout(scm: cheribuildSCM, poll: args.get("poll", true), changelog: args.get("changelog", true))
}