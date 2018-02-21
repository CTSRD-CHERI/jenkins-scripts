def setGitHubStatusBasedOnCurrentResult(Map args, String context, String result, String message) {
    if (result == null)
        result = currentBuild.result
    if (result == null)
        result = 'PENDING'
    if (message == null || message.isEmpty())
        message = "${env.JOB_NAME}: ${currentBuild.description}"
    def githubCommitStatusContext = context ? context : "jenkins/${env.JOB_NAME}"

    Map options = [$class            : 'GitHubCommitStatusSetter',
                   errorHandlers     : [[$class: 'ShallowAnyErrorHandler']],
                   // errorHandlers: [[$class: "ChangingBuildStatusErrorHandler", result: "UNSTABLE"]],
                   contextSource     : [$class: "ManuallyEnteredCommitContextSource", context: githubCommitStatusContext],
                   /*statusResultSource: [
                           $class: 'ConditionalStatusResultSource',
                           results: [
                                   [$class: 'BetterThanOrEqualBuildResult', result: 'SUCCESS', state: 'SUCCESS', message: message],
                                   [$class: 'BetterThanOrEqualBuildResult', result: 'UNSTABLE', state: 'FAILURE', message: message],
                                   [$class: 'BetterThanOrEqualBuildResult', result: 'FAILURE', state: 'FAILURE', message: message],
                                   [$class: 'AnyBuildResult', message: 'Something went wrong', state: 'ERROR']
                           ]
                   ]*/
                   statusResultSource: [$class : 'ConditionalStatusResultSource',
                                        results: [[$class: 'AnyBuildResult', message: message, state: result]]]
                   // statusResultSource: [ $class: "ConditionalStatusResultSource", results: [[$class: "AnyBuildResult", message: message, state: 'SUCCESS']] ]
    ]
    def gitHubCommitSHA = args?.GIT_COMMIT
    def gitHubRepoURL = args?.GIT_URL
    if (gitHubCommitSHA)
        githubNotifierOptions['commitShaSource'] = [$class: "ManuallyEnteredShaSource", sha: gitHubCommitSHA]
    if (gitHubRepoURL) {
        if (gitHubRepoURL.endsWith('.git')) {
            gitHubRepoURL = gitHubRepoURL.substring(0, gitHubRepoURL.indexOf('.git'))
        }
        githubNotifierOptions['reposSource'] = [$class: "ManuallyEnteredRepositorySource", url: gitHubRepoURL]
    }
    echo("GitHub notifier options = ${options}")
    step(options)
}

def call(Map scmInfo, Map<String, String> args = [:]) {
    setGitHubStatusBasedOnCurrentResult(scmInfo, args.get('context', null),
            args.get('result', null), args.get('message', ''))
}
