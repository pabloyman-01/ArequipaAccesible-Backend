-- Pilot approved by the project owner on 2026-10-22.
-- Internal project classification derived from the supplied Stitch mockup.
-- These codes are not claimed to be an official OMAPED catalogue.
INSERT INTO distritos(nombre) VALUES ('Arequipa (Cercado)') ON CONFLICT(nombre) DO NOTHING;
INSERT INTO tipos_barrera(codigo,nombre,descripcion) VALUES
('PROY-01','Rampa rota o inexistente','Catalogo interno del proyecto: falta de rampa o deterioro que impide el acceso.'),
('PROY-02','Vereda con desmonte u obstaculo','Catalogo interno del proyecto: materiales, cables o residuos que bloquean el paso.'),
('PROY-03','Semaforo sonoro averiado','Catalogo interno del proyecto: senal sonora o vibratoria de cruce que no funciona.'),
('PROY-04','Escalon sin rampa de acceso','Catalogo interno del proyecto: desnivel sin alternativa accesible.'),
('PROY-05','Comercio o letrero en vereda','Catalogo interno del proyecto: elementos comerciales que reducen el espacio peatonal.'),
('PROY-06','Deterioro de adoquin o laja','Catalogo interno del proyecto: superficie rota, suelta o con huecos.')
ON CONFLICT(codigo) DO NOTHING;
