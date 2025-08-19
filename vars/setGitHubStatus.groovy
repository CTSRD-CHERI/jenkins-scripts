/*

If it's not working see https://issues.jenkins-ci.org/browse/JENKINS-39482?focusedCommentId=323486&page=com.atlassian.jira.plugin.system.issuetabpanels:comment-tabpanel#comment-323486

Note: The GitHubCommitStatusSetter requires that the Git Server is defined under *Manage Jenkins > Configure System > GitHub > GitHub Servers*. Otherwise the GitHubCommitStatusSetter is not able to resolve the repository name properly and you would see an empty list of repos:

[...]
[Set GitHub commit status (universal)] PENDING on repos [] (sha:xxxxxxx) with context:test/mycontext
[...]
 */

def call(Map<String, String> args = [:]) {
    try {
        String context = args.getOrDefault('context', null)
        String result = args.getOrDefault('result', null)
        String message = args.getOrDefault('message', '')
        boolean includeTestStatus = args.getOrDefault('includeTestStatus', true)
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
            message = "${prettyJobName}: ${description} ${result}"
        }

        if (currentBuild.durationString && result != 'PENDING') {
            String duration = currentBuild.durationString.replaceAll(/and counting$/, "")
            message += " in ${duration}"
        }

        if (includeTestStatus)
            message += "\n${getTestStatus()

        // Map from Jenkins states to GitHub states: https://github.com/github-api/github-api/blob/master/src/main/java/org/kohsuke/github/GHCommitState.java
        if (result == 'UNSTABLE')
            result = 'FAILURE';
        else if (result == 'FAILURE')
            result = 'ERROR';

        // Maximum github message length is 140
        if (message.length() > 140) {
            echo("Truncating ${message} to 140 chars")
            message = message.substring(0, 139)
        }

        String githubCommitStatusContext = context ? context : "jenkins/${prettyJobName}"
        // Remove the unnecessary -pipeline suffix
        if (githubCommitStatusContext.indexOf('-pipeline/') > 0)
            githubCommitStatusContext = githubCommitStatusContext.replace('-pipeline/', '/')

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
        // Require GIT_URL to exist (must not be null)
        def gitHubRepoURL = args.GIT_URL
        def githubAccount = null
        def githubRepo = null
        if (gitHubRepoURL) {
            if (gitHubRepoURL.endsWith('.git')) {
                gitHubRepoURL = gitHubRepoURL.substring(0, gitHubRepoURL.lastIndexOf('.git'))
            }
            def githubParts = gitHubRepoURL.split('/');
            if (githubParts.size() < 2) {
                error("WRONG REPO URL: ${gitHubRepoURL}");
                return;
            }
            githubAccount = githubParts[-2]
            githubRepo = githubParts[-1]
            if (githubAccount != "CTSRD-CHERI") {
                echo("Not setting status on CTSRD-CHERI repo?? ${gitHubRepoURL}")
            }
            options['reposSource'] = [$class: "ManuallyEnteredRepositorySource", url: gitHubRepoURL]
        } else {
            echo("GIT_URL not set, args = ${args}")
            error("GIT_URL")
        }
        def newGitHubStatusSetterArgs = [
                // credentialsId: 'ctsrd-jenkins-new-github-api-key',
                credentialsId: 'github-app-cheri-jenkins',
                context: githubCommitStatusContext,
                description: message,
                status: result,
                // sha: 'aaaaa',
                repo: githubRepo,
                account: githubAccount,
        ]
        // TODO: githubNotify account: 'CTSRD-CHERI', context: 'ci/foo', credentialsId: 'ctsrd-jenkins-new-github-api-key', description: 'Building', repo: 'qemu', sha: 'aaaaa', status: 'PENDING', targetUrl: ''
        def gitHubCommitSHA = args?.GIT_COMMIT
        if (gitHubCommitSHA) {
            options['commitShaSource'] = [$class: "ManuallyEnteredShaSource", sha: gitHubCommitSHA]
            newGitHubStatusSetterArgs['sha'] = gitHubCommitSHA
        }
        echo("GitHub notifier options = ${newGitHubStatusSetterArgs}")
        githubNotify(newGitHubStatusSetterArgs)
        echo("Done setting github status")
        // echo("GitHub notifier options = ${options}")
        // old: step(options)
    } catch (e) {
        e.printStackTrace()
        echo("Could not set GitHub commit status: ${e}")
        // TODO: mark the current build as failed if github is unreachable?
        // currentBuild.result = 'FAILURE'
    }
}
