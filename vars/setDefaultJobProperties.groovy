import com.cloudbees.groovy.cps.NonCPS
import hudson.AbortException

class DefaultJobProperties {
    static boolean alreadyCalled = false;
}

// The properties() function **sets** job properties, deleting all previously set ones!
// See e.g. https://issues.jenkins-ci.org/browse/JENKINS-44848
// Hopefully this workaround works
@NonCPS
def getCurrentProperties() {
    hudson.model.Job<?, ?> job = currentBuild?.rawBuild?.parent;
    return job?.getAllProperties();
}

def call(List args) {
    if (DefaultJobProperties.alreadyCalled) {
        error("SetDefaultJobProperties called more than once!")
        throw new AbortException("setDefaultJobProperties called more than once!")
    }
    stage ("Set job properties") {
        try {
            newProperties = [
                    // compressBuildLog()  Broken, see https://issues.jenkins-ci.org/browse/JENKINS-54678
                    disableConcurrentBuilds(),
                    disableResume(),
                    // Note: not added for all projects: copyArtifactPermission('*'),
                    // Those that want artifacts to be copied should set this flag
                    durabilityHint('PERFORMANCE_OPTIMIZED'),
                    pipelineTriggers([
                            githubPush(),
                            pollSCM('@daily'),
                            issueCommentTrigger('.*test this please.*'),
                    ]),
            ] + args
            properties(newProperties)
        } catch (e) {
            echo("FAILED TO SET PROPERTIES: ${e}")
        }
    }
    // Return the current properties
    return getCurrentProperties();
}
