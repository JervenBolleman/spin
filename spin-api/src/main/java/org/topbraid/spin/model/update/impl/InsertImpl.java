/*******************************************************************************
 * Copyright (c) 2009 TopQuadrant, Inc.
 * All rights reserved. 
 *******************************************************************************/
package org.topbraid.spin.model.update.impl;

import org.topbraid.spin.model.print.PrintContext;
import org.topbraid.spin.model.update.Insert;
import org.topbraid.spin.vocabulary.SP;

import com.hp.hpl.jena.enhanced.EnhGraph;
import com.hp.hpl.jena.graph.Node;

@Deprecated
public class InsertImpl extends UpdateImpl implements Insert {

	public InsertImpl(Node node, EnhGraph graph) {
		super(node, graph);
	}

	
	public void print(PrintContext p) {
		printComment(p);
		printPrefixes(p);
		p.printIndentation(p.getIndentation());
		p.printKeyword("INSERT");
		printGraphIRIs(p, "INTO");
		printTemplates(p, SP.insertPattern, null, true, null);
		printWhere(p);
	}
}
