import com.cloudbees.groovy.cps.NonCPS
import hudson.model.JobProperty
import groovy.json.JsonBuilder


// The properties() function **sets** job properties, deleting all previously set ones!
// See e.g. https://issues.jenkins-ci.org/browse/JENKINS-44848
// Hopefully this workaround works
@NonCPS
def call(Map args) {
    hudson.model.Job<?, ?> job = currentBuild.rawBuild.parent;
    echo("Properties before: ${new JsonBuilder(job.getAllProperties()).toPrettyString()}")
    for (JobProperty<?> prop : args) {
        job.removeProperty(prop);
    }
    echo("Properties after: ${new JsonBuilder(job.getAllProperties()).toPrettyString()}")
}
