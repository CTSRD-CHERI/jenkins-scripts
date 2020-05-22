def call(Map<String, String> args = [:]) {
    if (!env.CHANGE_ID) {
        echo("WARNING: This should only be called for pull requests")
        return true
    }
    // Depends on https://github.com/jenkinsci/pipeline-github-plugin/pull/77
    // if (pullRequest.draft) {
    //	echo("Skipping Jenkins for pull request since draft flag was set")
    //	return
    //}
    if (!pullRequest.mergeable) {
        error("Pull request is not mergeable -> Won't build!")
        return false
    }
    echo "BUILD CAUSES: ${currentBuild.buildCauses}"
    boolean manualBuild = false
    currentBuild.buildCauses.eachWithIndex { item, index ->
        echo "Build cause ${index}: ${item} -- class=${item._class}"
        if ("${item._class}" == 'hudson.model.Cause$UserIdCause')
            manualBuild = true
    }
    echo "IS MANUAL BUILD: ${manualBuild}"
    if (manualBuild) {
        return true
    }
    String skipLabel = 'NO-JENKINS'
    String alwaysRunLabel = 'ALWAYS-JENKINS'
    boolean hasSkipLabel = false
    boolean hasAlwaysRunLabel = false
    pullRequest.labels.eachWithIndex { item, label ->
        echo "PR Label ${index}: ${item}"
        if (label == skipLabel)
            hasSkipLabel = true
        if (label == alwaysRunLabel)
            hasAlwaysRunLabel = true
    }
    if (hasSkipLabel) {
        echo("Skipping Jenkins for pull request since '${skipLabel}' label was set")
        return false
    }
    echo "Checking if PR has already been built:"
    boolean alreadyRun = false
    for (status in pullRequest.statuses) {
        // If the latest commit already has some statuses don't build again
        if (status.state != 'pending') {
            if (!alreadyRun) {
                echo "Found non-pending status for HEAD commit: ${pullRequest.head}, State: ${status.state}, Context: ${status.context}, URL: ${status.targetUrl}"
            }
            alreadyRun = true
            break
        }
    }
    echo "PR HEAD commit already built: ${alreadyRun}"
    if (alreadyRun && !hasAlwaysRunLabel) {
        echo "This pull request has already been tested, trigger a build manually or add the '${alwaysRunLabel}' label to re-test against latest HEAD"
        echo "PR COMMENTS:"
        def jenkinsComment = null
        lock('github_issue_comment_lock') {
            // some block
            for (comment in pullRequest.comments) {
                // echo "Author: ${comment.user}, Comment: ${comment.body}"
                if (comment.body.contains('pull request has already been tested')) {
                    jenkinsComment = comment
                }
            }
            if (jenkinsComment == null) {
                def comment = pullRequest.comment("Changes to base branch detected, but this pull request has already been tested.\n To re-test against latest HEAD trigger a build manually or add the '${alwaysRunLabel}' label.")
                // pullRequest.editComment(comment.id, 'Live long and prosper.')
            } else {
                echo "Jenkins comment already exists: ${jenkinsComment.user}, Comment: ${jenkinsComment.body}"
            }
        }
        return false
    }
    echo "Will build PR!"
    return true;
}
