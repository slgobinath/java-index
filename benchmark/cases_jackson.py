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

"""Benchmark queries on jackson-databind 2.23.0, fixed before any server was run.

The same kinds of query as cases.py (spring-petclinic), on a project about 50 times larger: 1,300
Java files, 260k lines. Libraries are jackson-core and jackson-annotations. Positions are written
the same way: (file, a unique substring of the line, the token on that line, occurrence).
"""

PKG = "src/main/java/com/fasterxml/jackson/databind/"
OBJECT_MAPPER = PKG + "ObjectMapper.java"

# The readiness probe: a definition into project code that every server must answer.
PROBE = "definition: project method"

CASES = [
    {
        "name": "definition: project method",
        "method": "textDocument/definition",
        "at": (OBJECT_MAPPER, "return readValue(content, _typeFactory.constructType(valueType));", "constructType"),
        "expect": {"locations": ["TypeFactory.java:public JavaType constructType(Type type)"]},
    },
    {
        "name": "definition: inherited method",
        "method": "textDocument/definition",
        "at": (PKG + "node/ObjectNode.java", "JsonPointer ptr = _jsonPointerIfValid(exprOrProperty);", "_jsonPointerIfValid"),
        "expect": {"locations": ["BaseJsonNode.java:_jsonPointerIfValid"]},
    },
    {
        "name": "definition: library method",
        "method": "textDocument/definition",
        "at": (OBJECT_MAPPER, "final BufferRecycler br = _jsonFactory._getBufferRecycler();", "_getBufferRecycler"),
        "expect": {"library": "JsonFactory"},
    },
    {
        "name": "definition: JDK method after lambda",
        "method": "textDocument/definition",
        "at": (PKG + "ext/NioPathDeserializer.java", ".collect(Collectors.joining(", "collect"),
        "expect": {"library": "Stream"},
    },
    {
        "name": "typeDefinition: local variable",
        "method": "textDocument/typeDefinition",
        "at": (PKG + "node/ObjectNode.java", "JsonPointer ptr = _jsonPointerIfValid(exprOrProperty);", "ptr"),
        "expect": {"library": "JsonPointer"},
    },
    {
        "name": "hover: library method",
        "method": "textDocument/hover",
        "at": (OBJECT_MAPPER, "return sw.getAndClear();", "getAndClear"),
        "expect": {"hover_contains": ["getAndClear", "String"]},
    },
    {
        "name": "references: getRawClass (many callers)",
        "method": "textDocument/references",
        "at": (PKG + "JavaType.java", "public final Class<?> getRawClass()", "getRawClass"),
        "expect": {"min_count": 50},
    },
    {
        "name": "references: one overload of readValue",
        "method": "textDocument/references",
        "at": (OBJECT_MAPPER, "public <T> T readValue(String content, Class<T> valueType)", "readValue"),
        "expect": {"min_count": 1},
    },
    {
        "name": "documentHighlight: local variable",
        "method": "textDocument/documentHighlight",
        "at": (OBJECT_MAPPER, "final BufferRecycler br = _jsonFactory._getBufferRecycler();", "br"),
        "expect": {"min_count": 3},
    },
    {
        "name": "implementation: subclasses of NumericNode",
        "method": "textDocument/implementation",
        "at": (PKG + "node/NumericNode.java", "public abstract class NumericNode", "NumericNode"),
        "expect": {"contains": ["IntNode", "LongNode", "DoubleNode", "DecimalNode", "BigIntegerNode"]},
    },
    {
        "name": "typeHierarchy: supertypes of ObjectNode",
        "method": "typeHierarchy/supertypes",
        "at": (PKG + "node/ObjectNode.java", "public class ObjectNode", "ObjectNode"),
        "expect": {"contains": ["ContainerNode"]},
    },
    {
        "name": "typeHierarchy: subtypes of ContainerNode",
        "method": "typeHierarchy/subtypes",
        "at": (PKG + "node/ContainerNode.java", "public abstract class ContainerNode", "ContainerNode"),
        "expect": {"contains": ["ArrayNode", "ObjectNode"]},
    },
    {
        "name": "callHierarchy: incoming calls",
        "method": "callHierarchy/incomingCalls",
        "at": (PKG + "ser/BeanSerializerFactory.java", "protected void removeSetterlessGetters(", "removeSetterlessGetters"),
        "expect": {"contains": ["findBeanProperties"]},
    },
    {
        "name": "callHierarchy: outgoing calls",
        "method": "callHierarchy/outgoingCalls",
        "at": (PKG + "ser/BeanSerializerFactory.java", "protected void removeSetterlessGetters(", "removeSetterlessGetters"),
        "expect": {"contains": ["removeIf"]},
    },
    {
        "name": "signatureHelp: library call",
        "method": "textDocument/signatureHelp",
        "at": (PKG + "type/MapLikeType.java", 'return String.format("[map-like type', "map"),
        "expect": {"signature_contains": "format("},
    },
    {
        "name": "documentSymbol: ObjectMapper.java",
        "method": "textDocument/documentSymbol",
        "file": OBJECT_MAPPER,
        "expect": {"contains": ["ObjectMapper", "readValue", "writeValueAsString"]},
    },
    {
        "name": "workspace/symbol: BeanDeserializerFac",
        "method": "workspace/symbol",
        "query": "BeanDeserializerFac",
        "expect": {"contains": ["BeanDeserializerFactory"]},
    },
]
