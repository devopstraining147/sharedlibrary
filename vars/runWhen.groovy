import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

def call(boolean condition, Closure body) {
    def config = [:]
    body.resolveStrategy = Closure.OWNER_FIRST
    body.delegate = config

    if (condition) {
        body()
    } else {
        Utils.markStageSkippedForConditional(STAGE_NAME)
    }
}