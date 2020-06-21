import com.cloudbees.groovy.cps.NonCPS
import hudson.AbortException

class DefaultJobProperties {
    static List values = [
        disableConcurrentBuilds(),
        disableResume(),
        // Note: not added for all projects: copyArtifactPermission('*'),
        // Those that want artifacts to be copied should set this flag
        durabilityHint('PERFORMANCE_OPTIMIZED'),
        pipelineTriggers([githubPush(), pollSCM('@daily'), issueCommentTrigger('.*test this please.*')])
    ]
    static boolean alreadyCalled = false;
}

// The properties() function **sets** job properties, deleting all previously set ones!
// See e.g. https://issues.jenkins-ci.org/browse/JENKINS-44848
// Hopefully this workaround works
@NonCPS
def call(List args, boolean copyArtifacts=false) {
    if (DefaultJobProperties.alreadyCalled) {
        error("SetDefaultJobProperties called more than once!")
        throw new AbortException("SetDefaultJobProperties called more than once!")
    }
    stage ("Set job properties") {
        try {
            newProperties = args + DefaultJobProperties.values
            if (copyArtifacts) {
                newProperties = newProperties + [copyArtifactPermission('*')]
            }
            properties(newProperties)
        } catch (e) {
            echo("FAILED TO SET PROPERTIES: ${e}")
        }
    }
    // Return the current properties
    hudson.model.Job<?, ?> job = currentBuild.rawBuild.parent;
    return job.getAllProperties();
}
