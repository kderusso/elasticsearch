/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.expression.function.scalar.string;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.compute.ann.Evaluator;
import org.elasticsearch.compute.operator.EvalOperator.ExpressionEvaluator;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.tree.NodeInfo;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.function.Example;
import org.elasticsearch.xpack.esql.expression.function.FunctionInfo;
import org.elasticsearch.xpack.esql.expression.function.OptionalArgument;
import org.elasticsearch.xpack.esql.expression.function.Param;
import org.elasticsearch.xpack.esql.expression.function.scalar.EsqlScalarFunction;
import org.elasticsearch.xpack.esql.io.stream.PlanStreamInput;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.elasticsearch.xpack.esql.core.expression.TypeResolutions.ParamOrdinal.FIRST;
import static org.elasticsearch.xpack.esql.core.expression.TypeResolutions.ParamOrdinal.FOURTH;
import static org.elasticsearch.xpack.esql.core.expression.TypeResolutions.ParamOrdinal.SECOND;
import static org.elasticsearch.xpack.esql.core.expression.TypeResolutions.ParamOrdinal.THIRD;
import static org.elasticsearch.xpack.esql.core.expression.TypeResolutions.isString;
import static org.elasticsearch.xpack.esql.core.expression.TypeResolutions.isType;

/**
 * Extract snippets function, that extracts the most relevant snippets from a given input string
 */
public class ExtractSnippets extends EsqlScalarFunction implements OptionalArgument {
    public static final NamedWriteableRegistry.Entry ENTRY = new NamedWriteableRegistry.Entry(
        Expression.class,
        "ExtractSnippets",
        ExtractSnippets::new
    );

    private static final int DEFAULT_NUM_SNIPPETS = 1;
    private static final int DEFAULT_SNIPPET_LENGTH = 10; // TODO determine a good default. 512, Elastic reranker token limit?

    // toSearch - the field or other calculated string content to search
    private final Expression toSearch;
    // str - the string we are searching toSearch for relevant snippets
    private final Expression str;
    private final Expression numSnippets;

    // I added the length here for this function because it will be used for more than just reranking, but
    // it makes sense to be optional/have a default value for when it is called for reranking purposes
    private final Expression snippetLength;

    @FunctionInfo(
        returnType = "string", // TODO is this the correct return type, or should it be some sort of list?
        description = """
            Extracts the most relevant snippets to return from a given input string""",
        examples = @Example(file = "string", tag = "extract_snippets")
    )
    public ExtractSnippets(
        Source source,
        @Param(name = "to_search", type = { "keyword" }, description = "The input string") Expression toSearch,
        @Param(name = "str", type = { "keyword", "text" }, description = "The input string") Expression str,
        @Param(
            optional = true,
            name = "num_snippets",
            type = { "integer" },
            description = "The number of snippets to return. Defaults to " + DEFAULT_NUM_SNIPPETS
        ) Expression numSnippets,
        @Param(
            optional = true,
            name = "snippet_length",
            type = { "integer" },
            description = "The length of snippets to return. Defaults to " + DEFAULT_SNIPPET_LENGTH
        ) Expression snippetLength
    ) {
        super(source, numSnippets == null ? Collections.singletonList(str) : Arrays.asList(str, numSnippets));
        this.toSearch = toSearch;
        this.str = str;
        this.numSnippets = numSnippets;
        this.snippetLength = snippetLength;
    }

    private ExtractSnippets(StreamInput in) throws IOException {
        this(
            Source.readFrom((PlanStreamInput) in),
            in.readNamedWriteable(Expression.class),
            in.readNamedWriteable(Expression.class),
            in.readNamedWriteable(Expression.class),
            in.readNamedWriteable(Expression.class)
        );
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        source().writeTo(out);
        out.writeNamedWriteable(toSearch);
        out.writeNamedWriteable(str);
        out.writeOptionalNamedWriteable(numSnippets);
        out.writeOptionalNamedWriteable(snippetLength);
    }

    @Override
    public String getWriteableName() {
        return ENTRY.name;
    }

    @Override
    public DataType dataType() {
        return DataType.KEYWORD; // TODO should this be TEXT or KEYWORD?
    }

    @Override
    protected TypeResolution resolveType() {
        if (childrenResolved() == false) {
            return new TypeResolution("Unresolved children");
        }

        TypeResolution resolution = isString(toSearch, sourceText(), FIRST);
        if (resolution.unresolved()) {
            return resolution;
        }

        resolution = isString(str, sourceText(), SECOND);
        if (resolution.unresolved()) {
            return resolution;
        }

        resolution = numSnippets == null
            ? TypeResolution.TYPE_RESOLVED
            : isType(numSnippets, dt -> dt == DataType.INTEGER, sourceText(), THIRD, "integer");
        if (resolution.unresolved()) {
            return resolution;
        }

        return snippetLength == null
            ? TypeResolution.TYPE_RESOLVED
            : isType(numSnippets, dt -> dt == DataType.INTEGER, sourceText(), FOURTH, "integer");
    }

    @Override
    public boolean foldable() {
        return toSearch.foldable()
            && str.foldable()
            && (numSnippets == null || numSnippets.foldable())
            && (snippetLength == null || snippetLength.foldable());
    }

    // TODO - It makes sense for this to return a list of strings (one string for each returned snippet)
    // However, the are currently no Types that support List<String>
    // We can either create a new Type for List<String> or turn this into something else - String? BytesRef?
    @Evaluator
    static List<String> process(BytesRef toSearch, BytesRef str, int numSnippets, int snippetLength) {
        if (toSearch == null || toSearch.length == 0 || str == null || str.length == 0) {
            return Collections.emptyList();
        }

        String utf8ToSearch = toSearch.utf8ToString();
        String utf8Str = str.utf8ToString();
        if (snippetLength > utf8ToSearch.length()) {
            return Collections.singletonList(utf8ToSearch);
        }

        // TODO - actually calculate snippets using search string, this truncation is just a placeholder
        List<String> snippets = new ArrayList<>(numSnippets);
        int pos = 0;
        for (int i = 0; i < numSnippets && pos < utf8ToSearch.length(); i++) {
            int end = Math.min(pos + snippetLength, utf8ToSearch.length());
            String snippet = utf8ToSearch.substring(pos, end);
            snippets.add(snippet);
            pos += snippetLength;
        }
        return snippets;
    }

    @Evaluator(extraName = "NoStart")
    static List<String> process(BytesRef toSearch, BytesRef str) {
        return process(toSearch, str, DEFAULT_NUM_SNIPPETS, DEFAULT_SNIPPET_LENGTH);
    }

    @Override
    public Expression replaceChildren(List<Expression> newChildren) {
        return new ExtractSnippets(
            source(),
            newChildren.get(0),
            newChildren.get(1),
            numSnippets == null ? null : newChildren.get(1),
            snippetLength == null ? null : newChildren.get(2)
        );
    }

    @Override
    protected NodeInfo<? extends Expression> info() {
        return NodeInfo.create(this, ExtractSnippets::new, toSearch, str, numSnippets, snippetLength);
    }

    @Override
    public ExpressionEvaluator.Factory toEvaluator(ToEvaluator toEvaluator) {
        // TODO this has to be auto-generated?
        return new ExtractSnippetsEvaluator.Factory(
            source(),
            toEvaluator.apply(toSearch),
            toEvaluator.apply(str),
            toEvaluator.apply(numSnippets),
            toEvaluator.apply(snippetLength)
        );
    }

    Expression toSearch() {
        return toSearch;
    }

    Expression str() {
        return str;
    }

    Expression numSnippets() {
        return numSnippets;
    }

    Expression snippetLength() {
        return snippetLength;
    }
}
