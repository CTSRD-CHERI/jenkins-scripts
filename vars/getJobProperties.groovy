import com.cloudbees.groovy.cps.NonCPS
import hudson.model.JobProperty
import groovy.json.JsonBuilder


// The properties() function **sets** job properties, deleting all previously set ones!
// See e.g. https://issues.jenkins-ci.org/browse/JENKINS-44848
// Hopefully this workaround works
@NonCPS
List<JobProperty<?>> call() {
    hudson.model.Job<?, ?> job = currentBuild.rawBuild.parent;
    return job.getAllProperties();
}
