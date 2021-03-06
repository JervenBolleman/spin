/*******************************************************************************
 * Copyright (c) 2009 TopQuadrant, Inc.
 * All rights reserved. 
 *******************************************************************************/
package org.topbraid.spin.model.impl;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.topbraid.spin.model.Aggregation;
import org.topbraid.spin.model.SPINFactory;
import org.topbraid.spin.model.Select;
import org.topbraid.spin.model.Variable;
import org.topbraid.spin.model.print.PrintContext;
import org.topbraid.spin.model.print.Printable;
import org.topbraid.spin.vocabulary.SP;

import com.hp.hpl.jena.enhanced.EnhGraph;
import com.hp.hpl.jena.graph.Node;
import com.hp.hpl.jena.rdf.model.RDFList;
import com.hp.hpl.jena.rdf.model.RDFNode;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.rdf.model.Statement;
import com.hp.hpl.jena.util.iterator.ExtendedIterator;


public class SelectImpl extends QueryImpl implements Select {
	
	public SelectImpl(Node node, EnhGraph eh) {
		super(node, eh);
	}

	@Override
	public List<Resource> getResultVariables() {
		List<Resource> results = new LinkedList<Resource>();
		for(RDFNode node : getList(SP.resultVariables)) {
			RDFNode e = SPINFactory.asExpression(node);
			results.add((Resource)e);
		}
		return results;
	}
	
	
    @Override
	public boolean isDistinct() {
		return hasProperty(SP.distinct, getModel().createTypedLiteral(true));
	}
	
	
    @Override
	public boolean isReduced() {
		return hasProperty(SP.reduced, getModel().createTypedLiteral(true));
	}


    @Override
	public void print(PrintContext p) {
		printComment(p);
		printPrefixes(p);
		p.printIndentation(p.getIndentation());
		p.printKeyword("SELECT");
		p.print(" ");
		if(isDistinct()) {
			p.printKeyword("DISTINCT");
			p.print(" ");
		}
		if(isReduced()) {
			p.printKeyword("REDUCED");
			p.print(" ");
		}
		List<Resource> vars = getResultVariables();
		if(vars.isEmpty()) {
			p.print("*");
		}
		else {
			for(Iterator<Resource> vit = vars.iterator(); vit.hasNext(); ) {
				Resource var = vit.next();
				if(var instanceof Variable) {
					if(var.hasProperty(SP.expression)) {
						printProjectExpression(p, (Variable) var);
					}
					else {
						((Variable)var).print(p);
					}
				}
				else if(var instanceof Aggregation) {
					((Printable)var).print(p);
				}
				else {
					p.print("(");
					((Printable)var).print(p);
					p.print(")");
				}
				if(vit.hasNext()) {
					p.print(" ");
				}
			}
		}
		printStringFrom(p);
		p.println();
		printWhere(p);
		printGroupBy(p);
		printHaving(p);
		printSolutionModifiers(p);
	}
	
	
	private void printGroupBy(PrintContext p) {
		Statement groupByS = getProperty(SP.groupBy);
		if(groupByS != null) {
			RDFList list = groupByS.getObject().as(RDFList.class);
			ExtendedIterator<RDFNode> it = list.iterator();
			if(it.hasNext()) {
				p.println();
				p.printIndentation(p.getIndentation());
				p.printKeyword("GROUP BY");
				while(it.hasNext()) {
					p.print(" ");
					RDFNode node = it.next();
					printNestedExpressionString(p, node);
				}
			}
		}
	}
	
	
	private void printHaving(PrintContext p) {
		Statement havingS = getProperty(SP.having);
		if(havingS != null) {
			RDFList list = havingS.getObject().as(RDFList.class);
			ExtendedIterator<RDFNode> it = list.iterator();
			if(it.hasNext()) {
				p.println();
				p.printIndentation(p.getIndentation());
				p.printKeyword("HAVING");
				while(it.hasNext()) {
					p.print(" ");
					RDFNode node = it.next();
					printNestedExpressionString(p, node);
				}
			}
		}
	}
	
	
	private void printProjectExpression(PrintContext p, Variable var) {
		p.print("((");
		RDFNode expr = var.getProperty(SP.expression).getObject();
		Printable expression = (Printable) SPINFactory.asExpression(expr);
		expression.print(p);
		p.print(") ");
		p.printKeyword("AS");
		p.print(" ");
		p.print(var.toString());
		p.print(")");
	}
}
