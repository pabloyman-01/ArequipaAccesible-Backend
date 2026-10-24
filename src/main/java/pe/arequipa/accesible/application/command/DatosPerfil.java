package pe.arequipa.accesible.application.command;

public record DatosPerfil(String nombres, String apellidos, String tipoDocumento, String documento,
                          String telefono, Long distritoId) {}
