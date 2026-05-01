package io.canonlog.sample

import io.canonlog.withCanonicalCoroutineContext
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class PropertyController {

    @PostMapping("/property/run")
    suspend fun runPlan(@RequestBody plan: Action): Map<String, String> = withCanonicalCoroutineContext {
        executePlan(plan, this)
        mapOf("ok" to "true")
    }
}
