package pe.arequipa.accesible.application.command;

/** PATCH of contact/display fields. Identity, email, roles and states are never writable. */
public record ActualizarPerfil(String nombres, String apellidos, String telefono, Long distritoId) {}
