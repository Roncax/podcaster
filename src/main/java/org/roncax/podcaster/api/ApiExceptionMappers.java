package org.roncax.podcaster.api;

import jakarta.ws.rs.core.Response;
import java.util.List;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import org.roncax.podcaster.runs.RunAlreadyActiveException;

public class ApiExceptionMappers {
    public record ErrorBody(String error, List<String> details) {}

    @ServerExceptionMapper
    public RestResponse<ErrorBody> invalid(InvalidRequestException e) {
        return RestResponse.status(Response.Status.BAD_REQUEST, new ErrorBody("Invalid request", e.errors()));
    }

    @ServerExceptionMapper
    public RestResponse<ErrorBody> active(RunAlreadyActiveException e) {
        return RestResponse.status(Response.Status.CONFLICT, new ErrorBody(e.getMessage(), List.of()));
    }

    @ServerExceptionMapper
    public RestResponse<ErrorBody> invalidPrompt(org.roncax.podcaster.prompts.InvalidPromptException e) {
        return RestResponse.status(Response.Status.BAD_REQUEST, new ErrorBody("Invalid prompt", e.errors()));
    }

    @ServerExceptionMapper
    public RestResponse<ErrorBody> noCandidates(org.roncax.podcaster.prompts.NoCandidatesException e) {
        return RestResponse.status(Response.Status.CONFLICT, new ErrorBody(e.getMessage(), List.of()));
    }
}
