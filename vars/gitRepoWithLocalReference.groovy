def fileOutsideWorkspaceExists(String path) {
    def returncode = sh returnStatus: true, script: "stat ${path}"
    return returncode == 0
}

def call(Map args) {
    def result = [
            $class                           : 'GitSCM',
            // branches                         : [[name: '*/master']],
            doGenerateSubmoduleConfigurations: false,
            submoduleCfg                     : [],
            userRemoteConfigs                : [[url: args.url]]
    ]
    String reponame = args.getOrDefault("refdir", null)
    String branch = args.getOrDefault("branch", null)
    if (branch != null) {
        // TODO: myabe we need $class: 'BranchSpec'  here
        result["branches"] = [[name: "*/${branch}"]]
    }
    if (reponame == null) {
        reponame = "${args.url}".substring("${args.url}".lastIndexOf('/') + 1)
        if (reponame.endsWith('.git'))
            reponame = reponame.substring(0, reponame.length() - 4)
    }
    String refdirAbs = "/var/tmp/git-reference-repos/" + reponame
    // echo("Guessed refdir: ${refdir}")
    def cloneOpt = [$class : 'CloneOption',
                    depth  : args.getOrDefault("depth", 1),
                    noTags : true,
                    shallow: args.getOrDefault("shallow", true),
                    reference : refdirAbs,
                    timeout: 5]
    result["extensions"] = [cloneOpt]
    return result
}
