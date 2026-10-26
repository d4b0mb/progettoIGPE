package it.ristomanager.cloud.ai;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tells managers whether the local language model is switched on and answering. */
@RestController
@RequestMapping("/api/v1/ai")
@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
class AiStatusController {

    private final LlmClient llm;
    private final AiProperties props;

    AiStatusController(LlmClient llm, AiProperties props) {
        this.llm = llm;
        this.props = props;
    }

    @GetMapping("/status")
    AiStatusResponse aiStatus() {
        return new AiStatusResponse(props.enabled(), llm.isReachable(), llm.modelName());
    }
}
