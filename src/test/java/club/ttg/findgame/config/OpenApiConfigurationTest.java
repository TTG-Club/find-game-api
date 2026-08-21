package club.ttg.findgame.config;

import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiConfigurationTest {

    @Test
    void doesNotRequireAuthenticationGloballyForPublicOperations() {
        OpenAPI openApi = new OpenApiConfiguration().findGameOpenApi();

        assertThat(openApi.getSecurity()).isNullOrEmpty();
        assertThat(openApi.getComponents().getSecuritySchemes()).containsKey("bearerAuth");
    }
}
