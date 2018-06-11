/*

If it's not working see https://issues.jenkins-ci.org/browse/JENKINS-39482?focusedCommentId=323486&page=com.atlassian.jira.plugin.system.issuetabpanels:comment-tabpanel#comment-323486

Note: The GitHubCommitStatusSetter requires that the Git Server is defined under *Manage Jenkins > Configure System > GitHub > GitHub Servers*. Otherwise the GitHubCommitStatusSetter is not able to resolve the repository name properly and you would see an empty list of repos:

[...]
[Set GitHub commit status (universal)] PENDING on repos [] (sha:xxxxxxx) with context:test/mycontext
[...]
 */


def setGitHubStatusBasedOnCurrentResult(Map args, String context, String result, String message, boolean includeTestStatus) {
    if (result == null)
        result = currentBuild.result
    if (result == null)
        result = 'PENDING'
    // Strip the -pipeline from JOB_NAME
    String prettyJobName = "${env.JOB_NAME}".replace('-pipeline/', '/')
    if (message == null || message.isEmpty()) {
        String description = currentBuild.displayName
        if (description == null)
            description = ''
        message = "${prettyJobName}: ${description}"
    }
    if (includeTestStatus)
        message += getTestStatus()

    if (currentBuild.durationString && result != 'PENDING') {
        message += "\n${result} after ${currentBuild.durationString}"
    }

    String githubCommitStatusContext = context ? context : "jenkins/${prettyJobName}"
    // Remove the unnecessary -pipeline suffix
    if (githubCommitStatusContext.indexOf('-pipeline/') > 0)
        githubCommitStatusContext = githubCommitStatusContext.replace('-pipeline/', '/')

    Map options = [$class            : 'GitHubCommitStatusSetter',
                   // errorHandlers     : [[$class: 'ShallowAnyErrorHandler']],
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
    if (gitHubCommitSHA)
        options['commitShaSource'] = [$class: "ManuallyEnteredShaSource", sha: gitHubCommitSHA]
    // Require GIT_URL to exist (can be null though)
    def gitHubRepoURL = args.GIT_URL
    if (gitHubRepoURL) {
        if (gitHubRepoURL.endsWith('.git')) {
            gitHubRepoURL = gitHubRepoURL.substring(0, gitHubRepoURL.indexOf('.git'))
        }
        options['reposSource'] = [$class: "ManuallyEnteredRepositorySource", url: gitHubRepoURL]
    }
    echo("GitHub notifier options = ${options}")
    step(options)
}

def call(Map scmInfo, Map<String, String> args = [:]) {
    setGitHubStatusBasedOnCurrentResult(scmInfo, args.get('context', null),
            args.get('result', null), args.get('message', ''), args.get('includeTestStatus', true))
}
