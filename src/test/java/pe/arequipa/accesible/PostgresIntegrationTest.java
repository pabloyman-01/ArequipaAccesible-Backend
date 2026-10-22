package pe.arequipa.accesible;

import java.sql.DriverManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.flywaydb.core.Flyway;
import pe.arequipa.accesible.application.command.*;
import pe.arequipa.accesible.application.usecase.PerfilUseCase;
import pe.arequipa.accesible.domain.model.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Dedicated disposable PostgreSQL only; never use an existing application database. */
@SpringBootTest(properties={"app.require-https=false","app.jwt.issuer=https://identity.example.test/auth/v1",
        "app.jwt.jwks-uri=https://identity.example.test/auth/v1/.well-known/jwks.json"})
@AutoConfigureMockMvc
@EnabledIf("databaseAvailable")
class PostgresIntegrationTest {
    static PostgreSQLContainer<?> container;
    static boolean databaseAvailable() {
        if (System.getenv("TEST_DB_URL") != null) return true;
        boolean available = DockerClientFactory.instance().isDockerAvailable();
        if (Boolean.getBoolean("requireDocker") && !available) throw new IllegalStateException("CI requires Docker for PostgreSQL tests");
        return available;
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) throws Exception {
        String url = System.getenv("TEST_DB_URL");
        String user = System.getenv("TEST_DB_USERNAME");
        String password = System.getenv().getOrDefault("TEST_DB_PASSWORD", "");
        if (url == null) {
            container = new PostgreSQLContainer<>("postgres:17-alpine");
            container.start();
            url = container.getJdbcUrl(); user = container.getUsername(); password = container.getPassword();
        }
        // Minimal Auth table fixture, NOT an implementation of Supabase Auth.
        try (var c = DriverManager.getConnection(url,user,password); var s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS auth");
            s.execute("CREATE TABLE IF NOT EXISTS auth.users(id uuid PRIMARY KEY)");
        }
        final String dbUrl=url, dbUser=user, dbPassword=password;
        r.add("spring.datasource.url", () -> dbUrl);
        r.add("spring.datasource.username", () -> dbUser);
        r.add("spring.datasource.password", () -> dbPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired PerfilUseCase perfiles;
    @Autowired Flyway flyway;
    @MockitoBean JwtDecoder decoder;
    UUID id;
    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE accesible.auditoria, accesible.usuario_roles, accesible.usuarios, accesible.distritos, auth.users CASCADE");
        id = UUID.randomUUID();
        auth(id);
        when(decoder.decode(anyString())).thenAnswer(i -> {
            String token = i.getArgument(0);
            return Jwt.withTokenValue(token).header("alg","RS256").subject(token)
                    .claim("email", token+"@example.test").claim("role","authenticated")
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        });
    }
    void auth(UUID user) { jdbc.update("INSERT INTO auth.users(id) VALUES (?)",user); }
    String bearer() { return "Bearer "+id; }
    String alta(String documento) { return """
        {"nombres":"Ana","apellidos":"Perez","tipoDocumento":"DNI","documento":"%s"}
        """.formatted(documento); }
    UsuarioPerfil registrar() { return perfiles.registrar(id,id+"@example.test",new DatosPerfil("Ana","Perez","DNI","12345678",null,null)); }
    @Test void migrationsValidAndAuthFkPresent() {
        flyway.validate();
        assertThat(Arrays.stream(flyway.info().applied()).filter(m -> m.getVersion() != null)).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname='usuarios_auth_fk'",Integer.class)).isEqualTo(1);
    }
    @Test void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }
    @Test void missingProfile404() throws Exception {
        mvc.perform(get("/api/usuarios/me").header("Authorization",bearer())).andExpect(status().isNotFound());
    }
    @Test void createAndRetryAreIdempotent() throws Exception {
        for (int i=0;i<2;i++) mvc.perform(put("/api/usuarios/me").header("Authorization",bearer())
                .contentType("application/json").content(alta("12345678")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.roles[0]").value("CIUDADANO"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accesible.usuarios",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accesible.usuario_roles",Integer.class)).isEqualTo(1);
    }
    @Test void contactPatchPreservesIdentityAndPrivileges() throws Exception {
        registrar();
        mvc.perform(patch("/api/usuarios/me").header("Authorization",bearer()).contentType("application/json")
                .content("{\"nombres\":\"Ana Maria\",\"telefono\":\"+51987654321\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nombres").value("Ana Maria"));
        assertThat(perfiles.consultar(id).documento()).isEqualTo("12345678");
        assertThat(perfiles.consultar(id).roles()).containsExactly(Rol.CIUDADANO);
    }
    @Test void conflictingDocumentRollsBackProfileAndRole() throws Exception {
        registrar();
        UUID other = UUID.randomUUID(); auth(other);
        mvc.perform(put("/api/usuarios/me").header("Authorization","Bearer "+other)
                .contentType("application/json").content(alta("12345678")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PERFIL_CONFLICTO"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accesible.usuarios",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accesible.usuario_roles",Integer.class)).isEqualTo(1);
    }
    @Test void databaseRequiresAuthSubject() {
        assertThatThrownBy(() -> perfiles.registrar(UUID.randomUUID(),"none@example.test",
                new DatosPerfil("Ana","Perez","DNI","87654321",null,null))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void inactiveDistrict422() throws Exception {
        mvc.perform(put("/api/usuarios/me").header("Authorization",bearer()).contentType("application/json")
                .content(alta("12345678").replace("}",",\"distritoId\":999}")))
                .andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accesible.usuarios",Integer.class)).isZero();
    }
    @Test void suspendedAccountRejectedImmediately() throws Exception {
        registrar();
        jdbc.update("UPDATE accesible.usuarios SET estado_cuenta='SUSPENDIDA' WHERE id=?",id);
        mvc.perform(get("/api/usuarios/me").header("Authorization",bearer()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CUENTA_SUSPENDIDA"));
    }
    @Test void roleChangesReadFromDatabaseEveryRequest() throws Exception {
        registrar();
        mvc.perform(get("/api/admin/usuarios").header("Authorization",bearer())).andExpect(status().isForbidden());
        jdbc.update("INSERT INTO accesible.usuario_roles SELECT ?,id FROM accesible.roles WHERE codigo='ADMINISTRADOR'",id);
        // Admin API itself belongs to iteration 4, so authorized access still returns 404.
        mvc.perform(get("/api/admin/usuarios").header("Authorization",bearer())).andExpect(status().isNotFound());
        jdbc.update("DELETE FROM accesible.usuario_roles WHERE usuario_id=? AND rol_id=(SELECT id FROM accesible.roles WHERE codigo='ADMINISTRADOR')",id);
        mvc.perform(get("/api/admin/usuarios").header("Authorization",bearer())).andExpect(status().isForbidden());
    }
    @RepeatedTest(5) void simultaneousRegistrationCreatesOneCitizen() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<UsuarioPerfil> call = () -> { start.await(); return registrar(); };
            var a = pool.submit(call); var b = pool.submit(call); start.countDown();
            assertThat(a.get(10,TimeUnit.SECONDS).id()).isEqualTo(id);
            assertThat(b.get(10,TimeUnit.SECONDS).id()).isEqualTo(id);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accesible.usuario_roles WHERE usuario_id=?",Integer.class,id)).isEqualTo(1);
    }
    @Test void privateSchemaHasRlsAndNoPublicAccess() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname='accesible' AND tablename <> 'flyway_schema_history' AND NOT rowsecurity",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.table_privileges WHERE table_schema='accesible' AND grantee='PUBLIC'",Integer.class)).isZero();
    }
    String script(String name) throws Exception {
        return Files.readString(Path.of("ops",name)).replaceAll("(?m)^\\\\set[^\\r\\n]*[\\r\\n]+", "");
    }
    void executeScript(String sql) throws Exception {
        try (var c = Objects.requireNonNull(jdbc.getDataSource()).getConnection(); var s = c.createStatement()) {
            try { s.execute(sql); } finally { s.execute("ROLLBACK"); }
        }
    }
    @Test void adminProvisionIsAuditedAndIdempotent() throws Exception {
        registrar();
        String sql = script("provision-admin.sql").replace(":'actor_id'","'"+id+"'")
                .replace(":'usuario_id'","'"+id+"'").replace(":'motivo'","'Bootstrap local test only'");
        executeScript(sql); executeScript(sql);
        assertThat(perfiles.consultar(id).roles()).contains(Rol.ADMINISTRADOR);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accesible.auditoria WHERE accion='OTORGAR_ADMINISTRADOR'",Integer.class)).isEqualTo(1);
    }
    @Test void adminProvisionFailureLeavesNoPrivilegesOrAudit() throws Exception {
        registrar();
        String sql = script("provision-admin.sql").replace(":'actor_id'","'"+UUID.randomUUID()+"'")
                .replace(":'usuario_id'","'"+id+"'").replace(":'motivo'","'Bootstrap local test only'");
        assertThatThrownBy(() -> executeScript(sql)).isInstanceOf(java.sql.SQLException.class);
        assertThat(perfiles.consultar(id).roles()).containsExactly(Rol.CIUDADANO);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accesible.auditoria",Integer.class)).isZero();
    }
    @Test void runtimeRoleCannotGrantAdministratorOrChangeAccountState() throws Exception {
        registrar();
        jdbc.execute("""
            DO $$ BEGIN
            IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='accesible_runtime_test') THEN
                CREATE ROLE accesible_runtime_test LOGIN NOSUPERUSER NOBYPASSRLS;
            END IF; END $$;
            """);
        executeScript(script("configure-runtime-role.sql").replace(":'runtime_role'","'accesible_runtime_test'")
                .replace(":\"runtime_role\"","\"accesible_runtime_test\""));
        try (var c = Objects.requireNonNull(jdbc.getDataSource()).getConnection(); var s = c.createStatement()) {
            s.execute("SET ROLE accesible_runtime_test");
            assertThat(s.executeQuery("SELECT id FROM accesible.usuarios").next()).isTrue();
            assertThatThrownBy(() -> s.executeUpdate("UPDATE accesible.usuarios SET estado_cuenta='SUSPENDIDA'"))
                    .isInstanceOf(java.sql.SQLException.class);
            assertThatThrownBy(() -> s.executeUpdate("INSERT INTO accesible.usuario_roles SELECT '"+id+"'::uuid,id FROM accesible.roles WHERE codigo='ADMINISTRADOR'"))
                    .isInstanceOf(java.sql.SQLException.class);
            s.execute("RESET ROLE");
        }
    }
    @AfterAll static void stop() { if (container != null) container.stop(); }
}
