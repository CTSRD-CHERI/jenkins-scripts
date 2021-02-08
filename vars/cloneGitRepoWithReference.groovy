def fileOutsideWorkspaceExists(String path) {
    def returncode = sh returnStatus: true, script: "stat ${path}"
    return returncode == 0
}

def call(Map args) {
    def cheribuildSCM = [
            $class                           : 'GitSCM',
            branches                         : [[name: args.getOrDefault('branches', '*/master')]],
            doGenerateSubmoduleConfigurations: false,
            submoduleCfg                     : [],
            userRemoteConfigs                : [[url: args.url,
                                                 credentialsId: args.getOrDefault('credentialsId', 'github-app-cheri-jenkins')]]
    ]
    String refdir = "/var/tmp/git-reference-repos/" + args.getOrDefault("refdir", "invalid-directory")
    if (fileOutsideWorkspaceExists(refdir)) {
        cheribuildSCM["extensions"] = [
                [$class: 'CloneOption', depth: 0, noTags: true, reference: refdir, shallow: false, timeout: 5]
        ]
        echo("Using reference repo for cheribuild")
    }
    return checkout(scm: cheribuildSCM, poll: args.getOrDefault("poll", true), changelog: args.getOrDefault("changelog", true))
}