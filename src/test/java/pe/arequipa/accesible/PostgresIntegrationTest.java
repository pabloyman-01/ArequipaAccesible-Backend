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
import pe.arequipa.accesible.domain.port.out.*;
import pe.arequipa.accesible.infrastructure.storage.LimpiezaArchivos;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.mock.web.MockMultipartFile;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Dedicated disposable PostgreSQL only; never use an existing application database. */
@SpringBootTest(properties={"app.require-https=false","app.jwt.issuer=https://identity.example.test/auth/v1",
        "app.cors-origins=http://localhost:3000",
        "app.jwt.jwks-uri=https://identity.example.test/auth/v1/.well-known/jwks.json",
        "app.reportes.habilitado=true","app.reportes.lat-min=-17","app.reportes.lat-max=-16",
        "app.reportes.lon-min=-72","app.reportes.lon-max=-71","app.reportes.limpieza-ms=3600000"})
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
    @MockitoBean ArchivoStoragePort storage;
    @Autowired ReporteRepositoryPort reportes;
    @Autowired AdministracionRepositoryPort administracion;
    @Autowired DashboardRepositoryPort dashboard;
    @Autowired LimpiezaArchivos limpieza;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    UUID id;
    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE accesible.auditoria, accesible.usuario_roles, accesible.usuarios, accesible.distritos, accesible.tipos_barrera, accesible.archivo_cargas, accesible.contadores_reporte, auth.users RESTART IDENTITY CASCADE");
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
    @Test void dashboardEmptyHasZeroForEveryStateAndSeverity() throws Exception {
        prepararReporte();admin(id);
        var result=listado(get("/api/admin/dashboard/resumen"));
        assertThat(result.get("total").asLong()).isZero();
        assertThat(result.get("pendientes").asLong()).isZero();
        assertThat(result.get("atendidos").asLong()).isZero();
        assertThat(result.get("rechazados").asLong()).isZero();
        assertThat(result.get("porEstado").size()).isEqualTo(6);
        result.get("porEstado").forEach(value->assertThat(value.asLong()).isZero());
        assertThat(result.get("porSeveridadDeclarada").size()).isEqualTo(3);
        result.get("porSeveridadDeclarada").forEach(value->assertThat(value.asLong()).isZero());
        assertThat(result.get("porTipoBarrera").isEmpty()).isTrue();
        assertThat(result.get("porDistrito").isEmpty()).isTrue();
        verifyNoInteractions(storage);
    }
    @Test void dashboardAggregatesAllOwnersWithoutMultiplyingHistoryOrPhotos() throws Exception {
        prepararReporte();admin(id);UUID other=otroCiudadano();
        for(int i=0;i<EstadoReporte.values().length;i++) {
            long report=filaListado(i%2==0?id:other,i+1,"2026-10-23T05:00:00Z",EstadoReporte.values()[i].name(),Severidad.values()[i%3].name(),1,1);
            for(int j=0;j<2;j++)jdbc.update("INSERT INTO accesible.historial_estados(reporte_id,estado_nuevo,actor_id,fecha_cambio) VALUES (?,'RECIBIDO',?,now())",report,id);
            for(int j=0;j<2;j++)jdbc.update("INSERT INTO accesible.reporte_fotos(reporte_id,object_key,mime_type,tamano_bytes,creado_en) VALUES (?,?,'image/png',20,now())",report,"test/"+report+"/"+j);
        }
        var result=listado(get("/api/admin/dashboard/resumen"));
        assertThat(result.get("total").asLong()).isEqualTo(6);
        assertThat(result.get("pendientes").asLong()).isEqualTo(4);
        assertThat(result.get("atendidos").asLong()).isEqualTo(1);
        assertThat(result.get("rechazados").asLong()).isEqualTo(1);
        result.get("porEstado").forEach(value->assertThat(value.asLong()).isEqualTo(1));
        result.get("porSeveridadDeclarada").forEach(value->assertThat(value.asLong()).isEqualTo(2));
        assertThat(result.at("/porTipoBarrera/0/cantidad").asLong()).isEqualTo(6);
        assertThat(result.at("/porDistrito/0/cantidad").asLong()).isEqualTo(6);
        assertThat(result.toString()).doesNotContain("ciudadanoId","documento","email","descripcion","direccion","object_key");
        assertThat(count("auditoria")).isZero();verifyNoInteractions(storage);
    }
    @Test void dashboardCombinesCreationDatesAndReportDistrictIncludingInactiveCatalogs() throws Exception {
        prepararReporte();admin(id);
        jdbc.update("INSERT INTO accesible.distritos(nombre) VALUES ('Segundo distrito de prueba')");
        jdbc.update("INSERT INTO accesible.tipos_barrera(codigo,nombre) VALUES ('TEST-02','Segundo tipo de prueba')");
        filaListado(id,1,"2026-10-23T04:59:59.999999Z","ATENDIDO","BAJA",1,1);
        filaListado(id,2,"2026-10-23T05:00:00Z","RECIBIDO","ALTA",2,2);
        filaListado(id,3,"2026-10-24T04:59:59.999999Z","RECHAZADO","MEDIA",2,2);
        filaListado(id,4,"2026-10-24T05:00:00Z","EN_PROCESO","ALTA",2,2);
        filaListado(id,5,"2026-10-23T12:00:00Z","ATENDIDO","BAJA",1,1);
        jdbc.update("UPDATE accesible.reportes SET fecha_actualizacion='2026-12-01T05:00:00Z'");
        jdbc.update("UPDATE accesible.distritos SET activo=false WHERE id=2");
        jdbc.update("UPDATE accesible.tipos_barrera SET activo=false WHERE id=2");
        var result=listado(get("/api/admin/dashboard/resumen?desde=2026-10-23&hasta=2026-10-23&distritoId=2"));
        assertThat(result.get("total").asLong()).isEqualTo(2);
        assertThat(result.get("pendientes").asLong()).isEqualTo(1);
        assertThat(result.get("rechazados").asLong()).isEqualTo(1);
        assertThat(result.at("/porEstado/RECIBIDO").asLong()).isEqualTo(1);
        assertThat(result.at("/porEstado/RECHAZADO").asLong()).isEqualTo(1);
        assertThat(result.at("/porDistrito/0/id").asLong()).isEqualTo(2);
        assertThat(result.at("/porTipoBarrera/0/nombre").asText()).isEqualTo("Segundo tipo de prueba");
        assertThat(result.at("/filtros/desde").asText()).isEqualTo("2026-10-23");
        assertThat(listado(get("/api/admin/dashboard/resumen?desde=2026-10-24")).get("total").asLong()).isEqualTo(1);
        assertThat(listado(get("/api/admin/dashboard/resumen?hasta=2026-10-22")).get("total").asLong()).isEqualTo(1);
        assertThat(listado(get("/api/admin/dashboard/resumen?distritoId=999")).get("total").asLong()).isZero();
        assertThat(listado(get("/api/admin/dashboard/resumen?desde=2027-01-01")).get("total").asLong()).isZero();
    }
    @Test void dashboardRejectsInvalidUnknownEmptyOrDuplicateFilters() throws Exception {
        prepararReporte();admin(id);
        for(String query:List.of("distritoId=0","distritoId=-1","distritoId=abc","distritoId=9223372036854775808",
                "desde=1999-12-31","hasta=+10000-01-01","desde=no-fecha","desde=2026-02-30",
                "desde=2026-10-24&hasta=2026-10-23","desde=","hasta=","distritoId=",
                "desde=2026-10-23&desde=2026-10-24","distritoId=1&distritoId=2","estado=RECIBIDO","ciudadanoId="+id,"limite=1"))
            mvc.perform(get("/api/admin/dashboard/resumen?"+query).header("Authorization",bearer())).andExpect(status().isBadRequest());
    }
    @Test void dashboardRequiresActiveAdminAndReflectsRoleRevocationImmediately() throws Exception {
        prepararReporte();String path="/api/admin/dashboard/resumen";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization",bearer())).andExpect(status().isForbidden());
        admin(id);listado(get(path));
        jdbc.update("DELETE FROM accesible.usuario_roles WHERE usuario_id=? AND rol_id=(SELECT id FROM accesible.roles WHERE codigo='ADMINISTRADOR')",id);
        mvc.perform(get(path).header("Authorization",bearer())).andExpect(status().isForbidden());
        admin(id);jdbc.update("UPDATE accesible.usuarios SET estado_cuenta='SUSPENDIDA' WHERE id=?",id);
        mvc.perform(get(path).header("Authorization",bearer())).andExpect(status().isForbidden());
    }
    @Test void dashboardReflectsAdministrativeTransitionWithoutWritingAnyExtraEvents() throws Exception {
        prepararReporte();admin(id);long report=filaListado(id,1,"2026-10-23T05:00:00Z","EN_PROCESO","ALTA",1,1);
        assertThat(listado(get("/api/admin/dashboard/resumen")).get("pendientes").asLong()).isEqualTo(1);
        mvc.perform(patchAdmin(report,"EN_PROCESO","ATENDIDO","Resuelto en prueba desechable")).andExpect(status().isOk());
        var result=listado(get("/api/admin/dashboard/resumen"));
        assertThat(result.get("total").asLong()).isEqualTo(1);
        assertThat(result.get("pendientes").asLong()).isZero();
        assertThat(result.get("atendidos").asLong()).isEqualTo(1);
        assertThat(count("auditoria")).isEqualTo(1);assertThat(count("historial_estados")).isEqualTo(1);
        verifyNoInteractions(storage);
    }
    @Test void dashboardWorksWithExistingRestrictedRuntimePermissions() throws Exception {
        prepararReporte();admin(id);filaListado(id,1,"2026-10-23T05:00:00Z","RECIBIDO","ALTA",1,1);
        jdbc.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='dashboard_runtime_test') THEN CREATE ROLE dashboard_runtime_test LOGIN NOSUPERUSER NOBYPASSRLS; END IF; END $$");
        executeScript(script("configure-runtime-role.sql").replace(":'runtime_role'","'dashboard_runtime_test'").replace(":\"runtime_role\"","\"dashboard_runtime_test\""));
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        tx.execute(status->{jdbc.execute("SET LOCAL ROLE dashboard_runtime_test");
            var result=dashboard.resumir(new ConsultaDashboard(null,null,1L));
            assertThat(result.total()).isEqualTo(1);assertThat(result.porEstado().get(EstadoReporte.RECIBIDO)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user",Boolean.class)).isFalse();
            return null;});
    }
    @Test void migrationsValidAndAuthFkPresent() {
        flyway.validate();
        assertThat(Arrays.stream(flyway.info().applied()).filter(m -> m.getVersion() != null)).hasSize(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname='usuarios_auth_fk'",Integer.class)).isEqualTo(1);
    }
    @Test void frontendCorsAllowsOnlyConfiguredOriginAndAuthorizationHeader() throws Exception {
        mvc.perform(options("/api/reportes").header("Origin","http://localhost:3000")
                .header("Access-Control-Request-Method","POST").header("Access-Control-Request-Headers","authorization,content-type"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin","http://localhost:3000"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        mvc.perform(options("/api/reportes").header("Origin","https://untrusted.example.test")
                .header("Access-Control-Request-Method","POST"))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
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
        mvc.perform(get("/api/admin/usuarios").header("Authorization",bearer())).andExpect(status().isOk());
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
        prepararReporte();
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
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        UUID pending=tx.execute(status -> {
            jdbc.execute("SET LOCAL ROLE accesible_runtime_test");
            return reportes.reservarCarga(id,"evidencias/"+UUID.randomUUID()+".png",5);
        });
        var input=new CrearReporte(1L,Severidad.ALTA,"Prueba de permisos",1L,"Dirección de prueba",new java.math.BigDecimal("-16.4"),new java.math.BigDecimal("-71.5"));
        var image=new ImagenValidada(png(),"image/png","png");
        var created=tx.execute(status -> {
            jdbc.execute("SET LOCAL ROLE accesible_runtime_test");
            return reportes.crear(input,id,pending,image,Instant.now());
        });
        assertThat(created.estado()).isEqualTo("RECIBIDO");
        var listed=tx.execute(status -> {
            jdbc.execute("SET LOCAL ROLE accesible_runtime_test");
            return reportes.listarPropios(id,new ConsultaReportes(20,null,null,null,null,null,null,null,null));
        });
        assertThat(listed).extracting(ResumenReporte::id).containsExactly(created.id());
        var history=tx.execute(status -> {
            jdbc.execute("SET LOCAL ROLE accesible_runtime_test");
            return reportes.historial(created.id(),id,false).orElseThrow();
        });
        assertThat(history).extracting(CambioEstadoReporte::estadoNuevo).containsExactly("RECIBIDO");
        // Lost COMMIT acknowledgement must not delete an already committed evidence object.
        reportes.limpiarCarga(pending,true,storage::eliminar);
        verify(storage,never()).eliminar(anyString());
    }
    @AfterAll static void stop() { if (container != null) container.stop(); }

    void prepararReporte() {
        registrar();
        jdbc.update("INSERT INTO accesible.distritos(nombre) VALUES ('Distrito de prueba, no oficial')");
        jdbc.update("INSERT INTO accesible.tipos_barrera(codigo,nombre) VALUES ('TEST-01','Barrera de prueba, no oficial')");
    }
    byte[] png() throws Exception {
        var out=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",out);
        return out.toByteArray();
    }
    String datosReporte() { return """
        {"tipoBarreraId":1,"severidadDeclarada":"ALTA","descripcion":"Rampa rota de prueba",
         "distritoId":1,"direccionReferencia":"Dirección ficticia de prueba","latitud":-16.3989,"longitud":-71.5350}
        """; }
    long filaListado(UUID owner, int numero, String fecha, String estado, String severidad, long tipo, long distrito) {
        return jdbc.queryForObject("""
            INSERT INTO accesible.reportes(codigo,ciudadano_id,tipo_barrera_id,distrito_id,severidad_declarada,
                descripcion,direccion_referencia,latitud,longitud,estado,fecha_creacion,fecha_actualizacion)
            VALUES (?,?,?,?,?,'Reporte tecnico de prueba','Referencia tecnica',-16.4,-71.5,?,?::timestamptz,?::timestamptz) RETURNING id
            """,Long.class,"ARQ-2026-%06d".formatted(numero),owner,tipo,distrito,severidad,estado,fecha,fecha);
    }
    UUID otroCiudadano() {
        UUID other=UUID.randomUUID();auth(other);
        perfiles.registrar(other,other+"@example.test",new DatosPerfil("Otro","Prueba","DNI","87654321",null,null));
        return other;
    }
    com.fasterxml.jackson.databind.JsonNode listado(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        var result=mvc.perform(request.header("Authorization",bearer())).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store")).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }
    @Test void ownListRequiresAuthenticationAndCitizenProfile() throws Exception {
        mvc.perform(get("/api/reportes")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/reportes").header("Authorization",bearer())).andExpect(status().isForbidden());
        registrar();
        jdbc.update("DELETE FROM accesible.usuario_roles WHERE usuario_id=?",id);
        jdbc.update("INSERT INTO accesible.usuario_roles SELECT ?,id FROM accesible.roles WHERE codigo='ADMINISTRADOR'",id);
        mvc.perform(get("/api/reportes").header("Authorization",bearer())).andExpect(status().isForbidden());
    }
    @Test void emptyOwnListHasNoCursor() throws Exception {
        prepararReporte();var page=listado(get("/api/reportes"));
        assertThat(page.get("elementos").size()).isZero();assertThat(page.get("limite").asInt()).isEqualTo(20);
        assertThat(page.get("hayMas").asBoolean()).isFalse();assertThat(page.get("siguienteCursor").isNull()).isTrue();
    }
    @Test void ownListPaginatesTiesWithoutRepeatingAfterConcurrentInsert() throws Exception {
        prepararReporte();
        String same="2026-10-23T05:00:00.123456Z";
        long first=filaListado(id,1,same,"RECIBIDO","ALTA",1,1);
        long second=filaListado(id,2,same,"RECIBIDO","ALTA",1,1);
        long third=filaListado(id,3,same,"RECIBIDO","ALTA",1,1);
        filaListado(otroCiudadano(),99,"2026-10-25T05:00:00Z","RECIBIDO","ALTA",1,1);
        var page=listado(get("/api/reportes").param("limite","2"));
        assertThat(page.get("elementos").size()).isEqualTo(2);
        assertThat(page.at("/elementos/0/id").asLong()).isEqualTo(third);
        assertThat(page.at("/elementos/1/id").asLong()).isEqualTo(second);
        assertThat(page.get("hayMas").asBoolean()).isTrue();
        String cursor=page.get("siguienteCursor").asText();
        filaListado(id,4,"2026-10-26T05:00:00Z","RECIBIDO","ALTA",1,1);
        long backdated=filaListado(id,5,"2026-10-22T05:00:00Z","RECIBIDO","ALTA",1,1);
        var next=listado(get("/api/reportes").param("limite","2").param("cursor",cursor));
        assertThat(next.get("elementos").size()).isEqualTo(2);
        assertThat(next.at("/elementos/0/id").asLong()).isEqualTo(first);
        assertThat(next.at("/elementos/1/id").asLong()).isEqualTo(backdated);
        assertThat(next.get("hayMas").asBoolean()).isFalse();assertThat(next.get("siguienteCursor").isNull()).isTrue();
        assertThat(page.toString()).doesNotContain("ciudadanoId","objectKey","fotos",id.toString());
        verifyNoInteractions(storage);
    }
    @Test void ownListFiltersIndividuallyAndTogetherWithInclusiveLimaDates() throws Exception {
        prepararReporte();
        jdbc.update("INSERT INTO accesible.distritos(nombre) VALUES ('Segundo distrito de prueba')");
        jdbc.update("INSERT INTO accesible.tipos_barrera(codigo,nombre) VALUES ('TEST-02','Segunda barrera de prueba')");
        long a=filaListado(id,1,"2026-10-23T04:59:59.999999Z","RECIBIDO","ALTA",1,1);
        long b=filaListado(id,2,"2026-10-23T05:00:00Z","ATENDIDO","BAJA",2,2);
        long c=filaListado(id,3,"2026-10-24T05:00:00Z","RECIBIDO","MEDIA",1,2);
        var cases=List.of(Map.entry("estado=ATENDIDO",b),Map.entry("severidadDeclarada=ALTA",a),
                Map.entry("tipoBarreraId=2",b),Map.entry("distritoId=1",a),Map.entry("codigo=arq-2026-000003",c),
                Map.entry("desde=2026-10-23&hasta=2026-10-23",b),
                Map.entry("estado=RECIBIDO&tipoBarreraId=1&distritoId=2&severidadDeclarada=MEDIA&desde=2026-10-23&hasta=2026-10-24",c));
        for(var entry:cases){
            var page=listado(get("/api/reportes?"+entry.getKey()));
            assertThat(page.get("elementos").size()).as(entry.getKey()).isEqualTo(1);
            assertThat(page.at("/elementos/0/id").asLong()).as(entry.getKey()).isEqualTo(entry.getValue());
        }
        assertThat(listado(get("/api/reportes?tipoBarreraId=999")).get("elementos").size()).isZero();
    }
    @Test void ownListAcceptsBothPageSizeBoundaries() throws Exception {
        prepararReporte();filaListado(id,1,"2026-10-23T05:00:00Z","RECIBIDO","ALTA",1,1);
        filaListado(id,2,"2026-10-24T05:00:00Z","RECIBIDO","ALTA",1,1);
        var minimum=listado(get("/api/reportes?limite=1"));
        assertThat(minimum.get("elementos").size()).isEqualTo(1);assertThat(minimum.get("hayMas").asBoolean()).isTrue();
        var maximum=listado(get("/api/reportes?limite=100"));
        assertThat(maximum.get("elementos").size()).isEqualTo(2);assertThat(maximum.get("hayMas").asBoolean()).isFalse();
    }
    @Test void ownListIncludesHistoricalReportsForInactiveCatalogues() throws Exception {
        prepararReporte();filaListado(id,1,"2026-10-23T05:00:00Z","RECIBIDO","ALTA",1,1);
        jdbc.update("UPDATE accesible.distritos SET activo=false");jdbc.update("UPDATE accesible.tipos_barrera SET activo=false");
        assertThat(listado(get("/api/reportes?tipoBarreraId=1&distritoId=1")).get("elementos").size()).isEqualTo(1);
    }
    @Test void dualRoleOwnListCannotReadAnotherCitizensReports() throws Exception {
        prepararReporte();long own=filaListado(id,1,"2026-10-23T05:00:00Z","RECIBIDO","ALTA",1,1);
        filaListado(otroCiudadano(),2,"2026-10-24T05:00:00Z","RECIBIDO","ALTA",1,1);
        jdbc.update("INSERT INTO accesible.usuario_roles SELECT ?,id FROM accesible.roles WHERE codigo='ADMINISTRADOR'",id);
        var page=listado(get("/api/reportes"));
        assertThat(page.get("elementos").size()).isEqualTo(1);assertThat(page.at("/elementos/0/id").asLong()).isEqualTo(own);
        assertThat(listado(get("/api/reportes?codigo=ARQ-2026-000002")).get("elementos").size()).isZero();
    }
    @Test void suspendedCitizenCannotListReports() throws Exception {
        prepararReporte();jdbc.update("UPDATE accesible.usuarios SET estado_cuenta='SUSPENDIDA' WHERE id=?",id);
        mvc.perform(get("/api/reportes").header("Authorization",bearer())).andExpect(status().isForbidden());
    }
    @Test void invalidListParametersAreRejected() throws Exception {
        prepararReporte();
        for(String query:List.of("limite=0","limite=101","limite=-1","limite=2147483648","limite=",
                "estado=INVENTADO","severidadDeclarada=EXTREMA","tipoBarreraId=0","distritoId=-1",
                "desde=no-es-fecha","desde=1999-12-31","desde=2026-10-24&hasta=2026-10-23",
                "codigo=%25","cursor=no-valido","cursor="+"x".repeat(129),"ciudadanoId="+id,"administrador=true",
                "sort=ciudadanoId","estado=RECIBIDO&estado=ATENDIDO")) {
            mvc.perform(get("/api/reportes?"+query).header("Authorization",bearer())).andExpect(status().isBadRequest());
        }
        for(String value:List.of("not-a-date|1","2026-10-23T05:00:00Z|-1","2026-10-23T05:00:00Z|999999999999999999999","2026-10-23T05:00:00Z|1|extra")) {
            String cursor=Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            mvc.perform(get("/api/reportes").param("cursor",cursor).header("Authorization",bearer())).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(storage);
    }
    MockMultipartFile datos(String body) { return new MockMultipartFile("datos","","application/json",body.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    MockMultipartFile foto() throws Exception { return new MockMultipartFile("foto","../../nombre-personal.png","image/png",png()); }
    long crearReporte() throws Exception {
        var response=mvc.perform(multipart("/api/reportes").file(datos(datosReporte())).file(foto()).header("Authorization",bearer()))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.estado").value("RECIBIDO")).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("id").asLong();
    }
    int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM accesible."+table,Integer.class); }
    long eventoHistorial(long reporte, String anterior, String nuevo, String motivo, String fecha) {
        return jdbc.queryForObject("""
            INSERT INTO accesible.historial_estados(reporte_id,estado_anterior,estado_nuevo,motivo,actor_id,fecha_cambio)
            VALUES (?,?,?,?,?,?::timestamptz) RETURNING id
            """,Long.class,reporte,anterior,nuevo,motivo,id,fecha);
    }
    void admin(UUID user) { jdbc.update("INSERT INTO accesible.usuario_roles SELECT ?,id FROM accesible.roles WHERE codigo='ADMINISTRADOR' ON CONFLICT DO NOTHING",user); }
    String cambio(String anterior,String nuevo,String motivo) {
        return "{\"estadoEsperado\":\""+anterior+"\",\"nuevoEstado\":\""+nuevo+"\",\"motivo\":"+(motivo==null?"null":"\""+motivo+"\"")+"}";
    }
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder patchAdmin(long report,String anterior,String nuevo,String motivo) {
        return patch("/api/admin/reportes/"+report+"/estado").header("Authorization",bearer()).contentType("application/json").content(cambio(anterior,nuevo,motivo));
    }
    @Test void administratorListsAllReportsButCitizenCollectionRemainsOwn() throws Exception {
        prepararReporte();admin(id);UUID other=otroCiudadano();
        filaListado(id,1,"2026-10-23T05:00:00Z","RECIBIDO","ALTA",1,1);
        long foreign=filaListado(other,2,"2026-10-24T05:00:00Z","EN_PROCESO","BAJA",1,1);
        var result=mvc.perform(get("/api/admin/reportes?limite=1").header("Authorization",bearer())).andExpect(status().isOk()).andReturn();
        var page=json.readTree(result.getResponse().getContentAsString());
        assertThat(page.at("/elementos/0/id").asLong()).isEqualTo(foreign);assertThat(page.get("hayMas").asBoolean()).isTrue();
        mvc.perform(get("/api/admin/reportes").param("cursor",page.get("siguienteCursor").asText()).header("Authorization",bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.elementos.length()").value(1));
        mvc.perform(get("/api/admin/reportes?ciudadanoId="+other+"&estado=EN_PROCESO&severidadDeclarada=BAJA&tipoBarreraId=1&distritoId=1&desde=2026-10-24&hasta=2026-10-24&codigo=ARQ-2026-000002")
                .header("Authorization",bearer())).andExpect(status().isOk()).andExpect(jsonPath("$.elementos.length()").value(1));
        assertThat(listado(get("/api/reportes")).get("elementos").size()).isEqualTo(1);
    }
    @Test void administrativeEndpointsRejectCitizensAndMissingSessions() throws Exception {
        prepararReporte();
        for(String path:List.of("/api/admin/reportes","/api/admin/usuarios")){
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization",bearer())).andExpect(status().isForbidden());
        }
        mvc.perform(patchAdmin(1,"RECIBIDO","EN_EVALUACION",null)).andExpect(status().isForbidden());
        mvc.perform(patch("/api/admin/usuarios/"+id+"/estado").header("Authorization",bearer()).contentType("application/json").content(cambio("ACTIVA","SUSPENDIDA","Prueba"))).andExpect(status().isForbidden());
    }
    @Test void transitionsPersistStateHistoryAndAuditAndRejectStaleRequests() throws Exception {
        prepararReporte();admin(id);long report=crearReporte();
        for(var pair:List.of(new String[]{"RECIBIDO","EN_EVALUACION"},new String[]{"EN_EVALUACION","EN_PROCESO"},new String[]{"EN_PROCESO","ATENDIDO"}))
            mvc.perform(patchAdmin(report,pair[0],pair[1],"Seguimiento técnico")).andExpect(status().isOk()).andExpect(jsonPath("$.estado").value(pair[1]));
        assertThat(jdbc.queryForObject("SELECT estado FROM accesible.reportes WHERE id=?",String.class,report)).isEqualTo("ATENDIDO");
        assertThat(count("historial_estados")).isEqualTo(4);assertThat(count("auditoria")).isEqualTo(4);
        mvc.perform(patchAdmin(report,"EN_PROCESO","ATENDIDO",null)).andExpect(status().isConflict());
        mvc.perform(patchAdmin(report,"ATENDIDO","EN_PROCESO",null)).andExpect(status().isConflict());
        mvc.perform(get("/api/reportes/"+report+"/historial").header("Authorization",bearer())).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(4));
    }
    @Test void rejectionValidationAndNoCrewAssignmentDoNotChangeData() throws Exception {
        prepararReporte();admin(id);long report=filaListado(id,1,"2026-10-23T05:00:00Z","EN_EVALUACION","ALTA",1,1);
        mvc.perform(patchAdmin(report,"EN_EVALUACION","ASIGNADO",null)).andExpect(status().isConflict());
        mvc.perform(patchAdmin(report,"EN_EVALUACION","RECHAZADO","  ")).andExpect(status().isUnprocessableEntity());
        mvc.perform(patchAdmin(report,"EN_EVALUACION","RECHAZADO","x".repeat(501))).andExpect(status().isUnprocessableEntity());
        assertThat(count("historial_estados")).isZero();assertThat(count("auditoria")).isZero();
        mvc.perform(patchAdmin(report,"EN_EVALUACION","RECHAZADO"," Fuera del alcance del piloto ")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT motivo FROM accesible.historial_estados",String.class)).isEqualTo("Fuera del alcance del piloto");
        mvc.perform(patchAdmin(report,"RECHAZADO","EN_EVALUACION","Prueba")).andExpect(status().isConflict());
    }
    @Test void concurrentTransitionsHaveOneWinnerAndOneHistoryEntry() throws Exception {
        prepararReporte();admin(id);long report=filaListado(id,1,"2026-10-23T05:00:00Z","RECIBIDO","ALTA",1,1);
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            Callable<Integer> call=()->{start.await();return mvc.perform(patchAdmin(report,"RECIBIDO","EN_EVALUACION",null)).andReturn().getResponse().getStatus();};
            var a=pool.submit(call);var b=pool.submit(call);start.countDown();
            assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        } finally {pool.shutdownNow();}
        assertThat(count("historial_estados")).isEqualTo(1);assertThat(count("auditoria")).isEqualTo(1);
    }
    @Test void auditFailureRollsBackReportStateAndHistory() throws Exception {
        prepararReporte();admin(id);long report=filaListado(id,1,"2026-10-23T05:00:00Z","RECIBIDO","ALTA",1,1);
        jdbc.execute("ALTER TABLE accesible.auditoria ADD CONSTRAINT test_admin_failure CHECK (accion<>'CAMBIAR_ESTADO_REPORTE')");
        try {mvc.perform(patchAdmin(report,"RECIBIDO","EN_EVALUACION",null)).andExpect(status().isConflict());}
        finally {jdbc.execute("ALTER TABLE accesible.auditoria DROP CONSTRAINT test_admin_failure");}
        assertThat(jdbc.queryForObject("SELECT estado FROM accesible.reportes WHERE id=?",String.class,report)).isEqualTo("RECIBIDO");
        assertThat(count("historial_estados")).isZero();assertThat(count("auditoria")).isZero();
    }
    @Test void adminUserListIsPagedFilteredAndMasksDocuments() throws Exception {
        prepararReporte();admin(id);UUID other=otroCiudadano();
        var first=mvc.perform(get("/api/admin/usuarios?limite=1").header("Authorization",bearer())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn();
        var page=json.readTree(first.getResponse().getContentAsString());assertThat(page.get("hayMas").asBoolean()).isTrue();
        var next=mvc.perform(get("/api/admin/usuarios?limite=1&cursor="+page.get("siguienteCursor").asText()).header("Authorization",bearer())).andExpect(status().isOk()).andReturn();
        var nextPage=json.readTree(next.getResponse().getContentAsString());
        assertThat(nextPage.at("/elementos/0/id").asText()).isNotEqualTo(page.at("/elementos/0/id").asText());assertThat(nextPage.get("hayMas").asBoolean()).isFalse();
        var result=mvc.perform(get("/api/admin/usuarios?rol=ADMINISTRADOR&estadoCuenta=ACTIVA").header("Authorization",bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.elementos.length()").value(1)).andExpect(jsonPath("$.elementos[0].documentoEnmascarado").value("****5678")).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("12345678","87654321","\"documento\":");
    }
    @Test void accountSuspensionReactivationAndSelfProtectionAreAudited() throws Exception {
        prepararReporte();admin(id);UUID other=otroCiudadano();
        String path="/api/admin/usuarios/"+other+"/estado";
        mvc.perform(patch(path).header("Authorization",bearer()).contentType("application/json").content(cambio("ACTIVA","SUSPENDIDA","Revisión interna"))).andExpect(status().isOk());
        mvc.perform(get("/api/usuarios/me").header("Authorization","Bearer "+other)).andExpect(status().isForbidden());
        mvc.perform(patch(path).header("Authorization",bearer()).contentType("application/json").content(cambio("ACTIVA","SUSPENDIDA","Revisión interna"))).andExpect(status().isConflict());
        mvc.perform(patch(path).header("Authorization",bearer()).contentType("application/json").content(cambio("SUSPENDIDA","ACTIVA","Revisión finalizada"))).andExpect(status().isOk());
        mvc.perform(get("/api/usuarios/me").header("Authorization","Bearer "+other)).andExpect(status().isOk());
        mvc.perform(patch("/api/admin/usuarios/"+id+"/estado").header("Authorization",bearer()).contentType("application/json").content(cambio("ACTIVA","SUSPENDIDA","Prueba"))).andExpect(status().isConflict());
        assertThat(count("auditoria")).isEqualTo(2);
    }
    @Test void adminValidationAndRoleRevocationAreEnforced() throws Exception {
        prepararReporte();admin(id);
        mvc.perform(patchAdmin(99999,"RECIBIDO","EN_EVALUACION",null)).andExpect(status().isNotFound());
        mvc.perform(patch("/api/admin/reportes/1/estado").header("Authorization",bearer()).contentType("application/json").content("{\"nuevoEstado\":\"EN_EVALUACION\"}")).andExpect(status().isBadRequest());
        mvc.perform(patch("/api/admin/reportes/1/estado").header("Authorization",bearer()).contentType("application/json").content("{\"estadoEsperado\":\"RECIBIDO\",\"nuevoEstado\":\"EN_EVALUACION\",\"actorId\":\"fake\"}")).andExpect(status().isBadRequest());
        for(String query:List.of("limite=0","limite=101","cursor=no-uuid","rol=FAKE","distritoId=-1","admin=true","limite=1&limite=2"))
            mvc.perform(get("/api/admin/usuarios?"+query).header("Authorization",bearer())).andExpect(status().isBadRequest());
        jdbc.update("DELETE FROM accesible.usuario_roles WHERE usuario_id=? AND rol_id=(SELECT id FROM accesible.roles WHERE codigo='ADMINISTRADOR')",id);
        mvc.perform(get("/api/admin/reportes").header("Authorization",bearer())).andExpect(status().isForbidden());
    }
    @Test void concurrentAdministratorsCannotSuspendEachOtherAndLeaveNoActiveAdmin() throws Exception {
        prepararReporte();admin(id);UUID other=otroCiudadano();admin(other);
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var a=pool.submit(()->{start.await();return mvc.perform(patch("/api/admin/usuarios/"+other+"/estado").header("Authorization",bearer()).contentType("application/json").content(cambio("ACTIVA","SUSPENDIDA","Prueba simultánea"))).andReturn().getResponse().getStatus();});
            var b=pool.submit(()->{start.await();return mvc.perform(patch("/api/admin/usuarios/"+id+"/estado").header("Authorization","Bearer "+other).contentType("application/json").content(cambio("ACTIVA","SUSPENDIDA","Prueba simultánea"))).andReturn().getResponse().getStatus();});
            start.countDown();assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,403);
        } finally {pool.shutdownNow();}
        assertThat(count("auditoria")).isEqualTo(1);
    }
    @Test void administrativeWritesWorkWithRestrictedRuntimeAndContextDoesNotLeak() throws Exception {
        prepararReporte();admin(id);UUID other=otroCiudadano();long report=filaListado(other,1,"2026-10-23T05:00:00Z","RECIBIDO","ALTA",1,1);
        jdbc.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='admin_runtime_test') THEN CREATE ROLE admin_runtime_test LOGIN NOSUPERUSER NOBYPASSRLS; END IF; END $$");
        executeScript(script("configure-runtime-role.sql").replace(":'runtime_role'","'admin_runtime_test'").replace(":\"runtime_role\"","\"admin_runtime_test\""));
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        tx.execute(status->{jdbc.execute("SET LOCAL ROLE admin_runtime_test");
            assertThat(jdbc.queryForObject("SELECT current_setting('app.actor_id',true)",String.class)).isNullOrEmpty();
            administracion.cambiarEstadoReporte(report,new TransicionReporte(EstadoReporte.RECIBIDO,EstadoReporte.EN_EVALUACION,null),id);return null;});
        tx.execute(status->{jdbc.execute("SET LOCAL ROLE admin_runtime_test");
            assertThat(jdbc.queryForObject("SELECT current_setting('app.actor_id',true)",String.class)).isNullOrEmpty();
            administracion.cambiarEstadoUsuario(other,new CambioEstadoCuenta(EstadoCuenta.ACTIVA,EstadoCuenta.SUSPENDIDA,"Prueba"),id);return null;});
        tx.execute(status->{jdbc.execute("SET LOCAL ROLE admin_runtime_test");
            administracion.cambiarEstadoUsuario(other,new CambioEstadoCuenta(EstadoCuenta.SUSPENDIDA,EstadoCuenta.ACTIVA,"Restauración"),id);return null;});
        tx.execute(status->{jdbc.execute("SET LOCAL ROLE admin_runtime_test");
            jdbc.queryForObject("SELECT set_config('app.actor_id',?,true)",String.class,other.toString());
            assertThat(jdbc.update("UPDATE accesible.reportes SET estado='EN_PROCESO' WHERE id=?",report)).isZero();return null;});
        assertThat(count("historial_estados")).isEqualTo(1);assertThat(count("auditoria")).isEqualTo(3);
    }
    @Test void accountAuditFailureRollsBackAndInvalidReasonIsRejected() throws Exception {
        prepararReporte();admin(id);UUID other=otroCiudadano();String path="/api/admin/usuarios/"+other+"/estado";
        mvc.perform(patch(path).header("Authorization",bearer()).contentType("application/json").content(cambio("ACTIVA","SUSPENDIDA"," "))).andExpect(status().isUnprocessableEntity());
        jdbc.execute("ALTER TABLE accesible.auditoria ADD CONSTRAINT test_account_audit_failure CHECK (accion<>'CAMBIAR_ESTADO_USUARIO')");
        try {mvc.perform(patch(path).header("Authorization",bearer()).contentType("application/json").content(cambio("ACTIVA","SUSPENDIDA","Prueba"))).andExpect(status().isConflict());}
        finally {jdbc.execute("ALTER TABLE accesible.auditoria DROP CONSTRAINT test_account_audit_failure");}
        assertThat(jdbc.queryForObject("SELECT estado_cuenta FROM accesible.usuarios WHERE id=?",String.class,other)).isEqualTo("ACTIVA");
        assertThat(count("auditoria")).isZero();
        mvc.perform(patch("/api/admin/usuarios/"+UUID.randomUUID()+"/estado").header("Authorization",bearer()).contentType("application/json").content(cambio("ACTIVA","SUSPENDIDA","Prueba"))).andExpect(status().isNotFound());
    }
    @Test void ownerCanReadInitialHistoryWithoutPrivateActorOrPhotoData() throws Exception {
        prepararReporte();long report=crearReporte();
        mvc.perform(get("/api/reportes/"+report+"/historial").header("Authorization",bearer()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].estadoAnterior").isEmpty())
                .andExpect(jsonPath("$[0].estadoNuevo").value("RECIBIDO"))
                .andExpect(jsonPath("$[0].motivo").isEmpty()).andExpect(jsonPath("$[0].fechaCambio").exists())
                .andExpect(jsonPath("$[0].actorId").doesNotExist()).andExpect(jsonPath("$[0].reporteId").doesNotExist());
        verify(storage,never()).descargar(anyString());
    }
    @Test void historyIsChronologicalAndTiesUseEventId() throws Exception {
        prepararReporte();long report=filaListado(id,1,"2026-10-23T05:00:00Z","ATENDIDO","ALTA",1,1);
        long middle=eventoHistorial(report,"RECIBIDO","EN_EVALUACION","Revisión técnica de prueba","2026-10-23T06:00:00.123456Z");
        long first=eventoHistorial(report,null,"RECIBIDO",null,"2026-10-23T05:00:00Z");
        long last=eventoHistorial(report,"EN_EVALUACION","ATENDIDO","Cierre técnico de prueba","2026-10-23T06:00:00.123456Z");
        long other=filaListado(otroCiudadano(),2,"2026-10-23T05:00:00Z","RECIBIDO","ALTA",1,1);
        eventoHistorial(other,null,"RECIBIDO","No debe aparecer","2026-10-23T05:00:00Z");
        var result=mvc.perform(get("/api/reportes/"+report+"/historial").header("Authorization",bearer()))
                .andExpect(status().isOk()).andReturn();
        var events=json.readTree(result.getResponse().getContentAsString());
        assertThat(events.size()).isEqualTo(3);
        assertThat(events.get(0).get("id").asLong()).isEqualTo(first);
        assertThat(events.get(1).get("id").asLong()).isEqualTo(middle);
        assertThat(events.get(2).get("id").asLong()).isEqualTo(last);
        assertThat(events.get(1).get("estadoAnterior").asText()).isEqualTo("RECIBIDO");
        assertThat(events.get(1).get("fechaCambio").asText()).isEqualTo("2026-10-23T06:00:00.123456Z");
        assertThat(events.get(2).get("motivo").asText()).isEqualTo("Cierre técnico de prueba");
        assertThat(events.toString()).doesNotContain("actorId","actor_id",id.toString(),"objectKey","No debe aparecer");
        verifyNoInteractions(storage);
    }
    @Test void existingReportWithoutHistoryReturnsEmptyArray() throws Exception {
        prepararReporte();long report=filaListado(id,1,"2026-10-23T05:00:00Z","RECIBIDO","ALTA",1,1);
        mvc.perform(get("/api/reportes/"+report+"/historial").header("Authorization",bearer()))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }
    @Test void foreignAndMissingHistoryReturnTheSameNotFoundError() throws Exception {
        prepararReporte();long report=filaListado(otroCiudadano(),1,"2026-10-23T05:00:00Z","RECIBIDO","ALTA",1,1);
        eventoHistorial(report,null,"RECIBIDO",null,"2026-10-23T05:00:00Z");
        for(String path:List.of("/api/reportes/"+report+"/historial?administrador=true&ciudadanoId="+id,"/api/reportes/999999/historial")) {
            mvc.perform(get(path).header("Authorization",bearer())).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NO_ENCONTRADO"))
                    .andExpect(jsonPath("$.message").value("Recurso no encontrado"));
        }
        verifyNoInteractions(storage);
    }
    @Test void administratorCanReadForeignHistoryAndRoleRevocationAppliesImmediately() throws Exception {
        prepararReporte();long report=filaListado(id,1,"2026-10-23T05:00:00Z","RECIBIDO","ALTA",1,1);
        eventoHistorial(report,null,"RECIBIDO",null,"2026-10-23T05:00:00Z");
        UUID admin=otroCiudadano();
        jdbc.update("DELETE FROM accesible.usuario_roles WHERE usuario_id=?",admin);
        jdbc.update("INSERT INTO accesible.usuario_roles SELECT ?,id FROM accesible.roles WHERE codigo='ADMINISTRADOR'",admin);
        mvc.perform(get("/api/reportes/"+report+"/historial").header("Authorization","Bearer "+admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].estadoNuevo").value("RECIBIDO"));
        jdbc.update("DELETE FROM accesible.usuario_roles WHERE usuario_id=?",admin);
        jdbc.update("INSERT INTO accesible.usuario_roles SELECT ?,id FROM accesible.roles WHERE codigo='CIUDADANO'",admin);
        mvc.perform(get("/api/reportes/"+report+"/historial").header("Authorization","Bearer "+admin))
                .andExpect(status().isNotFound());
        verifyNoInteractions(storage);
    }
    @Test void historyRequiresAuthenticationAndActiveProfile() throws Exception {
        mvc.perform(get("/api/reportes/1/historial")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/reportes/1/historial").header("Authorization",bearer())).andExpect(status().isForbidden());
        prepararReporte();long report=filaListado(id,1,"2026-10-23T05:00:00Z","RECIBIDO","ALTA",1,1);
        jdbc.update("UPDATE accesible.usuarios SET estado_cuenta='SUSPENDIDA' WHERE id=?",id);
        mvc.perform(get("/api/reportes/"+report+"/historial").header("Authorization",bearer())).andExpect(status().isForbidden());
        UUID admin=otroCiudadano();
        jdbc.update("INSERT INTO accesible.usuario_roles SELECT ?,id FROM accesible.roles WHERE codigo='ADMINISTRADOR'",admin);
        jdbc.update("UPDATE accesible.usuarios SET estado_cuenta='SUSPENDIDA' WHERE id=?",admin);
        mvc.perform(get("/api/reportes/"+report+"/historial").header("Authorization","Bearer "+admin)).andExpect(status().isForbidden());
    }
    @Test void historyIdentifiersAndUnsupportedMutationsAreHandledWithoutWrites() throws Exception {
        prepararReporte();long report=filaListado(id,1,"2026-10-23T05:00:00Z","RECIBIDO","ALTA",1,1);
        for(String value:List.of("abc","9223372036854775808")) {
            mvc.perform(get("/api/reportes/"+value+"/historial").header("Authorization",bearer())).andExpect(status().isBadRequest());
        }
        for(String value:List.of("0","-1")) {
            mvc.perform(get("/api/reportes/"+value+"/historial").header("Authorization",bearer())).andExpect(status().isNotFound());
        }
        mvc.perform(post("/api/reportes/"+report+"/historial").header("Authorization",bearer())).andExpect(status().isMethodNotAllowed());
        mvc.perform(patch("/api/reportes/"+report+"/historial").header("Authorization",bearer())).andExpect(status().isMethodNotAllowed());
        assertThat(count("historial_estados")).isZero();
        verifyNoInteractions(storage);
    }
    @Test void reportCreatedWithEvidenceHistoryAuditAndPrivateDetail() throws Exception {
        prepararReporte(); long reportId=crearReporte();
        assertThat(count("reportes")).isEqualTo(1); assertThat(count("reporte_fotos")).isEqualTo(1);
        assertThat(count("historial_estados")).isEqualTo(1); assertThat(count("auditoria")).isEqualTo(1);
        assertThat(count("archivo_cargas")).isZero();
        assertThat(jdbc.queryForObject("SELECT estado_nuevo FROM accesible.historial_estados",String.class)).isEqualTo("RECIBIDO");
        assertThat(jdbc.queryForObject("SELECT codigo FROM accesible.reportes",String.class)).matches("ARQ-[0-9]{4}-000001");
        verify(storage).subir(matches("evidencias/[a-f0-9-]{36}\\.png"),any());
        mvc.perform(get("/api/reportes/"+reportId).header("Authorization",bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.fotos[0].objectKey").doesNotExist())
                .andExpect(jsonPath("$.ciudadanoId").doesNotExist());
    }
    @Test void reportCannotBeReadByAnotherCitizenButAdminCanRead() throws Exception {
        prepararReporte(); long reportId=crearReporte();
        UUID other=UUID.randomUUID(); auth(other);
        perfiles.registrar(other,other+"@example.test",new DatosPerfil("Otro","Prueba","DNI","87654321",null,null));
        mvc.perform(get("/api/reportes/"+reportId).header("Authorization","Bearer "+other)).andExpect(status().isNotFound());
        mvc.perform(get("/api/reportes/"+reportId+"/fotos/1").header("Authorization","Bearer "+other)).andExpect(status().isNotFound());
        jdbc.update("INSERT INTO accesible.usuario_roles SELECT ?,id FROM accesible.roles WHERE codigo='ADMINISTRADOR'",other);
        mvc.perform(get("/api/reportes/"+reportId).header("Authorization","Bearer "+other)).andExpect(status().isOk());
        verify(storage,never()).descargar(anyString());
    }
    @Test void photoRequiresOwnershipAndIsServedWithoutCaching() throws Exception {
        prepararReporte(); long reportId=crearReporte();
        when(storage.descargar(anyString())).thenReturn(png());
        mvc.perform(get("/api/reportes/"+reportId+"/fotos/1").header("Authorization",bearer()))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png"))
                .andExpect(header().string("Cache-Control","no-store")).andExpect(content().bytes(png()));
        mvc.perform(get("/api/reportes/"+reportId+"/fotos/999").header("Authorization",bearer())).andExpect(status().isNotFound());
    }
    @Test void invalidImageRejectedBeforeStorageOrDatabaseWrite() throws Exception {
        prepararReporte();
        mvc.perform(multipart("/api/reportes").file(datos(datosReporte()))
                .file(new MockMultipartFile("foto","a.png","image/png","not a png".getBytes())).header("Authorization",bearer()))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("FOTO_INVALIDA"));
        assertThat(count("reportes")).isZero(); assertThat(count("archivo_cargas")).isZero(); verifyNoInteractions(storage);
    }
    @Test void oversizedImage413() throws Exception {
        prepararReporte();
        mvc.perform(multipart("/api/reportes").file(datos(datosReporte()))
                .file(new MockMultipartFile("foto","a.png","image/png",new byte[ImagenValidada.MAX_BYTES+1])).header("Authorization",bearer()))
                .andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(storage);
    }
    @Test void invalidCoordinatesAndEmptyDescription422() throws Exception {
        prepararReporte();
        for (String body:List.of(datosReporte().replace("-16.3989","91"),datosReporte().replace("Rampa rota de prueba","  "))) {
            mvc.perform(multipart("/api/reportes").file(datos(body)).file(foto()).header("Authorization",bearer()))
                    .andExpect(status().isUnprocessableEntity());
        }
        assertThat(count("reportes")).isZero(); verifyNoInteractions(storage);
    }
    @Test void inactiveCatalogue422() throws Exception {
        prepararReporte(); jdbc.update("UPDATE accesible.tipos_barrera SET activo=false");
        mvc.perform(multipart("/api/reportes").file(datos(datosReporte())).file(foto()).header("Authorization",bearer()))
                .andExpect(status().isUnprocessableEntity()); verifyNoInteractions(storage);
    }
    @Test void onlyActiveCataloguesArePublic() throws Exception {
        prepararReporte();
        jdbc.update("INSERT INTO accesible.distritos(nombre,activo) VALUES ('Inactivo de prueba',false)");
        mvc.perform(get("/api/catalogos/distritos")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/catalogos/tipos-barrera")).andExpect(status().isOk()).andExpect(jsonPath("$[0].codigo").value("TEST-01"));
    }
    @Test void missingPhoto400AndDuplicatePhoto422() throws Exception {
        prepararReporte();
        mvc.perform(multipart("/api/reportes").file(datos(datosReporte())).header("Authorization",bearer())).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/reportes").file(datos(datosReporte())).file(foto()).file(foto()).header("Authorization",bearer()))
                .andExpect(status().isUnprocessableEntity()); verifyNoInteractions(storage);
    }
    @Test void clientCannotChooseCitizenOrInitialState() throws Exception {
        prepararReporte();
        for (String extra:List.of("\"ciudadanoId\":\""+UUID.randomUUID()+"\"","\"estado\":\"ATENDIDO\"")) {
            mvc.perform(multipart("/api/reportes").file(datos(datosReporte().replace("}",","+extra+"}"))).file(foto()).header("Authorization",bearer()))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(storage);
    }
    @Test void postNeedsJwtAndActiveCitizen() throws Exception {
        prepararReporte();
        mvc.perform(multipart("/api/reportes").file(datos(datosReporte())).file(foto())).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE accesible.usuarios SET estado_cuenta='SUSPENDIDA' WHERE id=?",id);
        mvc.perform(multipart("/api/reportes").file(datos(datosReporte())).file(foto()).header("Authorization",bearer()))
                .andExpect(status().isForbidden()); verifyNoInteractions(storage);
    }
    @Test void networkFailureLeavesDurableCleanupIntent() throws Exception {
        prepararReporte(); doThrow(new ReporteException(ReporteException.Motivo.STORAGE_NO_DISPONIBLE)).when(storage).subir(anyString(),any());
        mvc.perform(multipart("/api/reportes").file(datos(datosReporte())).file(foto()).header("Authorization",bearer()))
                .andExpect(status().isServiceUnavailable());
        assertThat(count("reportes")).isZero(); assertThat(count("archivo_cargas")).isEqualTo(1);
        verify(storage,never()).eliminar(anyString());
        jdbc.update("UPDATE accesible.archivo_cargas SET limpiar_despues=now()-interval '1 minute'");
        limpieza.ejecutar(); verify(storage).eliminar(anyString()); assertThat(count("archivo_cargas")).isZero();
    }
    @Test void persistenceFailureRollsBackEverythingAndDeletesUploadedObject() throws Exception {
        prepararReporte();
        jdbc.execute("ALTER TABLE accesible.auditoria ADD CONSTRAINT test_fail_audit CHECK (accion <> 'CREAR_REPORTE')");
        try {
            mvc.perform(multipart("/api/reportes").file(datos(datosReporte())).file(foto()).header("Authorization",bearer()))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REPORTE_CONFLICTO"));
        } finally { jdbc.execute("ALTER TABLE accesible.auditoria DROP CONSTRAINT test_fail_audit"); }
        for(String table:List.of("reportes","reporte_fotos","historial_estados","auditoria","contadores_reporte","archivo_cargas")) assertThat(count(table)).as(table).isZero();
        verify(storage).eliminar(anyString());
    }
    @Test void failedCompensationIsRetriedFromDurableQueue() throws Exception {
        prepararReporte();
        doThrow(new ReporteException(ReporteException.Motivo.STORAGE_NO_DISPONIBLE)).when(storage).eliminar(anyString());
        UUID pending=reportes.reservarCarga(id,"evidencias/"+UUID.randomUUID()+".png",5);
        reportes.limpiarCarga(pending,true,storage::eliminar);
        assertThat(count("archivo_cargas")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT intentos FROM accesible.archivo_cargas",Integer.class)).isEqualTo(1);
        doNothing().when(storage).eliminar(anyString());
        jdbc.update("UPDATE accesible.archivo_cargas SET limpiar_despues=now()-interval '1 minute'");
        limpieza.ejecutar(); assertThat(count("archivo_cargas")).isZero();
    }
    @Test void rateLimitIsSharedAndAtomic() throws Exception {
        prepararReporte();
        for(int i=0;i<5;i++) reportes.reservarCarga(id,"evidencias/"+UUID.randomUUID()+".png",5);
        mvc.perform(multipart("/api/reportes").file(datos(datosReporte())).file(foto()).header("Authorization",bearer()))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After","60"));
        verifyNoInteractions(storage);
    }
    @RepeatedTest(3) void concurrentReportCodesAreUnique() throws Exception {
        prepararReporte();
        var input=new CrearReporte(1L,Severidad.ALTA,"Reporte concurrente de prueba",1L,"Dirección de prueba",
                new java.math.BigDecimal("-16.3989"),new java.math.BigDecimal("-71.5350"));
        var image=new ImagenValidada(png(),"image/png","png");
        UUID a=reportes.reservarCarga(id,"evidencias/"+UUID.randomUUID()+".png",5);
        UUID b=reportes.reservarCarga(id,"evidencias/"+UUID.randomUUID()+".png",5);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var latch=new CountDownLatch(1);
            var first=pool.submit(() -> { latch.await(); return reportes.crear(input,id,a,image,Instant.now()); });
            var second=pool.submit(() -> { latch.await(); return reportes.crear(input,id,b,image,Instant.now()); }); latch.countDown();
            assertThat(first.get(10,TimeUnit.SECONDS).codigo()).isNotEqualTo(second.get(10,TimeUnit.SECONDS).codigo());
        }
        assertThat(count("reportes")).isEqualTo(2); assertThat(count("archivo_cargas")).isZero();
    }
}
