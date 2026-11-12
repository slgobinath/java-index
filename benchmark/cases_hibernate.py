# Copyright 2026 Gobinath Loganathan
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Benchmark queries on Hibernate ORM 7.4.15, fixed before any server was run.

A multi-module Gradle build: 17,000 Java files, 2.1M lines, about 25 modules. The same kinds of
query as cases.py, plus one that crosses modules (hibernate-envers into hibernate-core), and
hierarchies that span hibernate-core, hibernate-community-dialects and hibernate-spatial.
Libraries include Jakarta Persistence. Positions are written as in cases.py.
"""

CORE = "hibernate-core/src/main/java/org/hibernate/"
SESSION_IMPL = CORE + "internal/SessionImpl.java"
STRING_HELPER = CORE + "internal/util/StringHelper.java"

# The readiness probe: a definition into project code that every server must answer.
PROBE = "definition: project method"

CASES = [
    {
        "name": "definition: project method",
        "method": "textDocument/definition",
        "at": (SESSION_IMPL, "getJdbcCoordinator().afterTransaction();", "afterTransaction"),
        "expect": {"locations": ["JdbcCoordinator.java:afterTransaction"]},
    },
    {
        "name": "definition: inherited method",
        "method": "textDocument/definition",
        "at": (SESSION_IMPL, "final var sessionOpenEvent = getEventMonitor().beginSessionOpenEvent();", "getEventMonitor"),
        "expect": {"locations": ["AbstractSharedSessionContract.java:getEventMonitor"]},
    },
    {
        "name": "definition: across modules",
        "method": "textDocument/definition",
        "at": ("hibernate-envers/src/main/java/org/hibernate/envers/internal/tools/EntityTools.java",
               ": sessionImplementor.getFactory().openTemporarySession();", "openTemporarySession"),
        "expect": {"locations": ["SessionFactoryImplementor.java:openTemporarySession"]},
    },
    {
        "name": "definition: library method",
        "method": "textDocument/definition",
        "at": (SESSION_IMPL, ".map( entry -> entityType.getJavaType().cast(", "getJavaType"),
        "expect": {"library": "metamodel/Type."},
    },
    {
        "name": "definition: JDK method after lambda",
        "method": "textDocument/definition",
        "at": (SESSION_IMPL, ".filter( entry -> entry.getKey().getEntityName().equals( entityName ) )", "equals"),
        "expect": {"library": "String"},
    },
    {
        "name": "typeDefinition: local variable",
        "method": "textDocument/typeDefinition",
        "at": (SESSION_IMPL, "final var entityClass = entity.getClass();", "entityClass"),
        "expect": {"library": "Class"},
    },
    {
        "name": "hover: library method",
        "method": "textDocument/hover",
        "at": (SESSION_IMPL, ".map( entry -> entityType.getJavaType().cast(", "getJavaType"),
        "expect": {"hover_contains": ["getJavaType", "Class"]},
    },
    {
        "name": "references: StringHelper.isEmpty (many callers)",
        "method": "textDocument/references",
        "at": (STRING_HELPER, "public static boolean isEmpty(@Nullable String string)", "isEmpty"),
        "expect": {"min_count": 100},
    },
    {
        "name": "references: one overload of qualify",
        "method": "textDocument/references",
        "at": (STRING_HELPER, "public static String qualify(String prefix, String name)", "qualify"),
        "expect": {"min_count": 1},
    },
    {
        "name": "documentHighlight: local variable",
        "method": "textDocument/documentHighlight",
        "at": (SESSION_IMPL, "final var persistenceContext = PersistenceContexts.createPersistenceContext( this );", "persistenceContext"),
        "expect": {"min_count": 3},
    },
    {
        "name": "implementation: subclasses of Dialect",
        "method": "textDocument/implementation",
        "at": (CORE + "dialect/Dialect.java", "public abstract class Dialect", "Dialect"),
        # H2 and PostgreSQL in hibernate-core, SQLite in hibernate-community-dialects.
        "expect": {"contains": ["H2Dialect", "PostgreSQLDialect", "MySQLDialect", "SQLiteDialect"]},
    },
    {
        "name": "typeHierarchy: supertypes of SessionImpl",
        "method": "typeHierarchy/supertypes",
        "at": (SESSION_IMPL, "public class SessionImpl", "SessionImpl"),
        "expect": {"contains": ["AbstractSharedSessionContract", "SessionImplementor"]},
    },
    {
        "name": "typeHierarchy: subtypes of PostgreSQLDialect",
        "method": "typeHierarchy/subtypes",
        "at": (CORE + "dialect/PostgreSQLDialect.java", "public class PostgreSQLDialect", "PostgreSQLDialect"),
        # hibernate-core, hibernate-community-dialects and hibernate-spatial.
        "expect": {"contains": ["PostgresPlusDialect", "SpannerPostgreSQLDialect", "PostgisPG95Dialect"]},
    },
    {
        "name": "callHierarchy: incoming calls",
        "method": "callHierarchy/incomingCalls",
        "at": (SESSION_IMPL, "private void fireLoadNoChecks(", "fireLoadNoChecks"),
        "expect": {"contains": ["fireLoad"]},
    },
    {
        "name": "callHierarchy: outgoing calls",
        "method": "callHierarchy/outgoingCalls",
        "at": (SESSION_IMPL, "private void fireLoadNoChecks(", "fireLoadNoChecks"),
        "expect": {"contains": ["pulseTransactionCoordinator", "fireEventOnEachListener"]},
    },
    {
        "name": "signatureHelp: library call",
        "method": "textDocument/signatureHelp",
        "at": (SESSION_IMPL, ".map( entry -> entityType.getJavaType().cast(", "entry", 1),
        "expect": {"signature_contains": "cast("},
    },
    {
        "name": "documentSymbol: SessionImpl.java",
        "method": "textDocument/documentSymbol",
        "file": SESSION_IMPL,
        "expect": {"contains": ["SessionImpl", "getEntityPersister", "afterOperation"]},
    },
    {
        "name": "workspace/symbol: PostgreSQLDial",
        "method": "workspace/symbol",
        "query": "PostgreSQLDial",
        "expect": {"contains": ["PostgreSQLDialect"]},
    },
]
