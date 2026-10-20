# AGENTS.md

## 1. Propósito y alcance

Este documento define las reglas obligatorias y no negociables para cualquier agente de IA que opere sobre este repositorio. 

- El agente actúa exclusivamente como implementador de tareas especificadas en el prompt.
- Solo trabajará sobre marcas `TODO:` en el código si el prompt las señala de forma explícita. Tiene prohibido buscar o resolver `TODO:` preexistentes por iniciativa propia.
- No tiene autoridad para decidir qué trabajar, solo cómo implementarlo dentro de los límites aquí establecidos.
- Cualquier acción no contemplada o que entre en conflicto con estas reglas exige **detener el proceso y consultar al usuario** antes de ejecutarse (ver sección 5.5).

## 2. Stack y contexto del proyecto

- **Lenguaje:** Kotlin (desarrollo para Android)
- **Sistema de build:** Gradle (Kotlin DSL)
- **UI:** Jetpack Compose (Material 3)
- **Arquitectura:** MVVM
- **Fuente de verdad de dependencias:** `{raiz}/app/build.gradle.kts` (u otros `build.gradle.kts` de módulos existentes)

## 3. Prohibiciones absolutas

Las siguientes acciones están estrictamente prohibidas en toda circunstancia, incluso si el agente las considera necesarias para completar la tarea:

- **Build system:** Modificar `{raiz}/app/build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml` o cualquier archivo de Gradle (solo lectura).
- **Ejecución de Gradle:** Ejecutar cualquier comando o tarea de Gradle (`./gradlew ...`). El agente no compila ni ejecuta tests; la verificación es exclusiva del usuario.
- **Mutación en Git:** Ejecutar comandos de git que alteren el estado del repositorio (`add`, `commit`, `push`, `pull`, `checkout`, `branch`, `merge`, `reset`, `rebase`, `stash`, `tag`, etc.).
- **Eliminación de código o archivos:** Eliminar archivos, directorios, clases, métodos, properties o bloques funcionales preexistentes. El agente solo crea o modifica; nunca borra.
- **Eliminación de imports:** Eliminar imports existentes bajo ninguna circunstancia. Si un import preexistente genera colisión o conflicto, aplicar el protocolo de detención (sección 5.5).
- **Ruptura directa de contratos:** Modificar directamente en el código real firmas públicas preexistentes o realizar cambios estructurales severos (estos casos exigen Modo Espejo, ver sección 5.1).
- **Alcance no solicitado:** Modificar código no especificado en el prompt. Cualquier mejora o cambio colateral detectado debe anotarse en `.agents/{modelo}/sugerencias.md`, nunca aplicarse.
- **Tests:** Modificar, eliminar o alterar archivos de tests existentes.
- **Espacios ajenos:** Leer, escribir o modificar directorios dentro de `.agents/` pertenecientes a otros modelos, o rutas fuera de `{raiz}/`.

## 4. Directorio de trabajo del agente: `.agents/{modelo}`

### 4.1 Identificación del modelo

El agente debe identificarse usando el nombre general de su familia de modelo en minúsculas y sin versión (ej. `gemini`, `claude`, `gpt`, `deepseek`, `qwen`, `llama`). Si no puede determinarlo con certeza, usará `.agents/llm` y lo dejará asentado en su reporte.

### 4.2 Reglas del directorio

- **Ruta:** `{raiz}/.agents/{modelo}/`
- Si el directorio no existe, el agente está autorizado a crearlo sin pedir confirmación.
- El agente no puede eliminar este directorio ni borrar archivos dentro de él (solo crearlos o actualizarlos).
- Prohibido acceder a `.agents/{otro_modelo}/`.
- El directorio `.agents/` es local y no se versiona en git.

### 4.3 Artefactos en `.agents/{modelo}/`

| Archivo | Propósito y cuándo se genera |
| :--- | :--- |
| `reporte.md` | **Obligatorio en cada intervención.** Bitácora técnica directa y concisa de los cambios realizados (ver sección 9). |
| `sugerencias.md` | Cuando el agente detecta cambios necesarios fuera de su alcance, dependencias faltantes, colisiones de imports o dudas de diseño. |
| `TODO.md` | Cuando el agente debe detener su proceso antes de terminar (ver sección 5.5). Contiene los pasos pendientes o las decisiones requeridas del usuario. |
| `<NombreArchivo>.kt` | **Archivo Espejo.** Se genera ÚNICAMENTE cuando una tarea requiere alterar firmas públicas preexistentes o reestructurar clases existentes. Contiene solo el bloque/clase propuesto y NO se aplica al archivo real hasta que el usuario lo autorice. |
| Otros (`.diff`, notas) | Opcional para apoyo técnico, respetando las reglas del directorio. |

## 5. Implementación de código y reglas de modificación

### 5.1 Modalidades de trabajo sobre el código

El agente opera bajo tres modalidades estrictamente delimitadas:

**1. Creación de archivos y paquetes nuevos (Directo en el proyecto):**
- Permitida de forma directa en `app/src/main/...` sin pedir confirmación previa cuando la tarea lo requiera.
- El agente puede crear nuevos archivos `.kt` (pantallas, componentes, ViewModels, temas, modelos) y crear subdirectorios/paquetes nuevos (ej. `ui/screens`, `ui/components`) respetando el paquete base.
- Al conectar estos archivos nuevos con archivos preexistentes, rigen las modalidades 2 y 3.

**2. Modificación directa (En el archivo real preexistente):**
Permitida sin confirmación previa siempre que **se respeten íntegramente los contratos públicos vigentes**:
- Poblar archivos preexistentes que solo contengan la declaración `package` (y/o imports iniciales), añadiendo libremente las clases, funciones `@Composable` públicas e imports necesarios.
- Añadir nuevas funciones, clases o properties (públicas o privadas) en archivos existentes, sin alterar ni romper firmas preexistentes.
- Completar stubs, métodos vacíos o marcas `TODO:` asignadas en el prompt, conservando intacta su firma original.
- Modificar el cuerpo o lógica interna de métodos existentes sin cambiar su visibilidad, nombre, parámetros, tipo de retorno ni modificadores (`suspend`, `inline`, etc.).

**3. Modo Espejo en `.agents/{modelo}/` (Sin tocar el archivo real):**
Obligatorio cuando completar la tarea exige romper contratos preexistentes:
- Alterar una firma pública preexistente (visibilidad, nombre, parámetros, retorno o modificadores).
- Alterar jerarquías de herencia, constructores públicos en uso o interfaces preexistentes.
- **Acción:** El agente NO modifica el archivo real. Escribe la propuesta en `.agents/{modelo}/<NombreArchivo>.kt`, justifica el cambio en `reporte.md` y detiene esa parte del flujo solicitando autorización en el chat.

### 5.2 Traducción de maquetas y esquemas visuales (ej. XML de draw.io, wireframes)

Cuando la tarea consista en convertir diagramas o bocetos externos a código Jetpack Compose, el agente NO debe bloquearse por detalles visuales menores:
- **Mapeo autónomo:** Tiene libertad para elegir contenedores (`Column`, `Row`, `Box`, `Card`, `Scaffold`) y componentes de Material 3 (`TextField`, `Button`, `Text`, `Icon`) que representen fielmente la geometría del esquema.
- **Ajuste al classpath actual:** Resolverá toda la UI únicamente con las dependencias e iconos ya disponibles en el proyecto (ej. `Icons.Default`, colores del tema o hexadecimales directos). Si un elemento visual requiere una librería o icono inexistente, usará un placeholder estándar y lo anotará en `reporte.md`.
- **Estado local de UI:** Para controles interactivos (inputs, switches, botones) sin ViewModel o contrato definido en el prompt, implementará estado local temporal (`remember { mutableStateOf(...) }`) y lambdas vacías (`onClick = {}`) para entregar código 100% funcional.

### 5.3 Dependencias faltantes

Si una tarea requiere obligatoriamente una librería no declarada en `app/build.gradle.kts` (y no aplica el uso de placeholders de la sección 5.2):
1. No modificar ningún archivo de Gradle.
2. Documentar en `.agents/{modelo}/sugerencias.md`: dependencia exacta, versión recomendada, motivo y bloque donde declararla.
3. Aplicar el protocolo de detención (sección 5.5).

### 5.4 Archivos de configuración y recursos

`AndroidManifest.xml`, `proguard-rules.pro`, `strings.xml`, `themes.xml` y demás archivos en `res/`:
- **Si el prompt lo solicita explícitamente:** El agente puede añadir nuevos recursos o entradas directamente (sin borrar ni renombrar los existentes).
- **Si el prompt NO lo menciona pero el agente lo considera necesario:** No debe modificarlos; debe detenerse y solicitar confirmación al usuario.

### 5.5 Protocolo de detención y consulta (Manejo de bloqueos y contradicciones)

Si durante el análisis o implementación el agente encuentra **ambigüedad crítica, contradicción entre reglas, colisión de imports, falta de dependencias o necesidad de acciones no autorizadas**:

1. **Detener el proceso** sobre el punto conflictivo (nunca adivinar APIs, no forzar workarounds destructivos ni violar reglas).
2. **Preservar el avance seguro:** Completar únicamente las partes de la tarea que sean independientes y seguras bajo las reglas 5.1.1 y 5.1.2.
3. **Documentar el bloqueo:**
   - Registrar la duda técnica, colisión o propuesta en `.agents/{modelo}/sugerencias.md` (o el archivo espejo en `.agents/{modelo}/<NombreArchivo>.kt`).
   - Listar en `.agents/{modelo}/TODO.md` los pasos exactos que quedan pendientes.
   - Reflejar el estado como `PARCIAL` o `BLOQUEADA` en `.agents/{modelo}/reporte.md`.
4. **Solicitar reanudación en el chat:** Informar claramente al usuario cuál fue el bloqueo y formular la pregunta concreta para que el usuario responda y el agente pueda retomar en la siguiente iteración.

## 6. Verificación de código

El agente tiene prohibido invocar Gradle. Toda validación por parte del agente se realizará mediante inspección estática de tipos, firmas, imports y coherencia sintáctica. El usuario ejecutará la compilación y los tests manualmente.

## 7. Convenciones de código

### 7.1 Arquitectura y tolerancia con código preexistente

- **Estándar para código nuevo:** MVVM con Jetpack Compose e inyección de dependencias por constructor/parámetros.
- **Tolerancia con código preexistente:** Las reglas de arquitectura y nomenclatura aplican **exclusivamente al código nuevo generado por el agente**. Si el repositorio contiene código previo con otros patrones (singletons manuales, métodos estáticos, nombres fuera de convención):
  - El agente debe consumir e interactuar con ese código tal como está, sin refactorizarlo ni alterarlo.
  - No debe reportar estas discrepancias en `sugerencias.md` salvo que impidan directamente cumplir la tarea.

### 7.2 Nomenclatura e idioma

- **Clases / Interfaces / Composables:** `PascalCase` en inglés (ej. `UserRepository`, `LoginScreen`).
- **Funciones / Métodos / Variables:** `camelCase` en inglés (ej. `loadUserData`, `isLoading`).
- **Constantes:** `UPPER_SNAKE_CASE` en inglés.
- **Comentarios en código y documentación (`.agents/`):** Español.
- **Strings de UI:** Español (o el idioma indicado en el prompt / maqueta).

## 8. Tests

- Prohibido modificar o eliminar archivos de test existentes.
- No crear tests nuevos salvo orden explícita en el prompt.

## 9. Reporte y comunicación

Priorizar la entrega de código; los reportes deben ser técnicos, directos y sin texto de relleno:

- **En el chat:** Resumen breve con los archivos creados/modificados, estado de la tarea y, si hubo detención, la consulta puntual para continuar en la siguiente iteración.
- **En `.agents/{modelo}/reporte.md`:** Changelog conciso que incluya:
  - Referencia breve de la tarea.
  - Archivos creados o modificados directamente (y resumen del cambio).
  - Archivos generados en Modo Espejo (si aplica) y motivo.
  - Bloqueos, riesgos o dependencias faltantes.
  - Estado final: `COMPLETA`, `PARCIAL` o `BLOQUEADA`.

## 10. Autonomía, sistema de archivos y comandos de shell

### 10.1 Permisos de lectura y exploración (Sin confirmación)

- **Permitido:** Lectura libre de archivos y directorios exclusivamente desde `{raiz}/` hacia adentro (`app/`, `docs/`, `gradle/`, `build.gradle.kts`, etc.) usando herramientas nativas del agente o comandos de solo lectura (`cat`, `grep`, `find`, `ls`, `head`, `tail`, `wc`, `git status`, `git diff`, `git log`).
- **Prohibido en lectura:**
  - Acceder a cualquier ruta fuera de `{raiz}/` (prohibido usar `..`, rutas del sistema o `~/`).
  - Leer `.agents/{otro_modelo}/`.
  - Leer archivos de credenciales o configuración local de máquina (`local.properties`, `.env`, `*.jks`, `*.keystore`).

### 10.2 Permisos de escritura y ejecución

- **Sin confirmación previa:**
  - Crear o actualizar archivos dentro de `{raiz}/.agents/{modelo}/`.
  - Crear archivos/paquetes nuevos y aplicar modificaciones directas autorizadas (sección 5.1.1 y 5.1.2) dentro de `{raiz}/app/src/main/`.
- **Requiere detención y confirmación previa del usuario:**
  - Cualquier escritura fuera de `app/src/main/` y `.agents/{modelo}/`.
  - Cualquier cambio que requiera alterar firmas públicas preexistentes (usar Modo Espejo).
- **Prohibido siempre:** Cualquier comando de eliminación (`rm`, `del`, borrado de código/imports), comandos de Gradle y mutaciones de Git.

## 11. Git

Prohibido cualquier comando de git que modifique el historial, índice o working tree. Solo se permiten consultas pasivas (`git status`, `git diff`, `git log`). El usuario gestiona íntegramente el control de versiones.

## 12. Resumen ejecutivo (TL;DR)

1. **Alcance estricto:** Ejecuta solo lo pedido en el prompt. Ignora `TODO:` no citados y respeta el código legacy existente sin refactorizarlo.
2. **Creación y edición segura:** Crear archivos/paquetes nuevos en `app/src/main/`, poblar archivos vacíos (con solo `package`) y editar cuerpos de métodos sin tocar su firma se hace **directamente en el archivo real**.
3. **Contratos blindados:** Si necesitas cambiar una firma pública existente → **NO toques el archivo real**, crea el espejo en `.agents/{modelo}/<NombreArchivo>.kt` y consulta.
4. **Cero borrados:** Prohibido borrar archivos, métodos, clases o `imports` existentes.
5. **Maquetas a Compose:** Traduce esquemas visuales (ej. draw.io) usando autónomamente Material 3, recursos/iconos ya disponibles y estado local (`remember`).
6. **Build y Git intocables:** Prohibido tocar `build.gradle.kts`, prohibido ejecutar `./gradlew` y prohibido mutar git (solo `status`/`diff`/`log`).
7. **Freno de seguridad:** Ante dudas, conflictos de imports o falta de librerías → detén el paso conflictivo, documenta en `.agents/{modelo}/` (`reporte.md`, `sugerencias.md`, `TODO.md`) y pregunta en el chat para continuar en la siguiente iteración.
