def setGitHubStatusBasedOnCurrentResult(Map args, String context, String result) {
	if (result == null)
		result = currentBuild.currentResult
	def message = "${env.JOB_NAME}: ${currentBuild.description}"
	def githubCommitStatusContext = context ? context : "jenkins/${env.JOB_NAME}"
	Map githubNotifierOptions = [
			$class: 'GitHubCommitStatusSetter',
			errorHandlers: [[$class: 'ShallowAnyErrorHandler']],
			// errorHandlers: [[$class: "ChangingBuildStatusErrorHandler", result: "UNSTABLE"]],
			contextSource: [$class: "ManuallyEnteredCommitContextSource", context: githubCommitStatusContext],
			/*statusResultSource: [
					$class: 'ConditionalStatusResultSource',
					results: [
							[$class: 'BetterThanOrEqualBuildResult', result: 'SUCCESS', state: 'SUCCESS', message: message],
							[$class: 'BetterThanOrEqualBuildResult', result: 'UNSTABLE', state: 'FAILURE', message: message],
							[$class: 'BetterThanOrEqualBuildResult', result: 'FAILURE', state: 'FAILURE', message: message],
							[$class: 'AnyBuildResult', message: 'Something went wrong', state: 'ERROR']
					]
			]*/
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
	echo("${githubNotifierOptions}")
	step(githubNotifierOptions)
}

def call(Map scmInfo, Map<String, String> args = []) {
	setGitHubStatusBasedOnCurrentResult(scmInfo, args.get('context', null), args.get('result', null))
}
