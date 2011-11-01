/*******************************************************************************
 * Copyright (c) 2009 TopQuadrant, Inc.
 * All rights reserved. 
 *******************************************************************************/
package org.topbraid.spin.constraints;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.topbraid.base.progress.ProgressMonitor;
import org.topbraid.spin.arq.ARQFactory;
import org.topbraid.spin.model.Argument;
import org.topbraid.spin.model.Ask;
import org.topbraid.spin.model.Construct;
import org.topbraid.spin.model.ElementList;
import org.topbraid.spin.model.QueryOrTemplateCall;
import org.topbraid.spin.model.SPINFactory;
import org.topbraid.spin.model.SPINInstance;
import org.topbraid.spin.model.Template;
import org.topbraid.spin.model.TemplateCall;
import org.topbraid.spin.statistics.SPINStatistics;
import org.topbraid.spin.system.SPINImports;
import org.topbraid.spin.system.SPINLabels;
import org.topbraid.spin.system.SPINModuleRegistry;
import org.topbraid.spin.util.CommandWrapper;
import org.topbraid.spin.util.JenaUtil;
import org.topbraid.spin.util.PropertyPathsGetter;
import org.topbraid.spin.util.QueryWrapper;
import org.topbraid.spin.util.SPINQueryFinder;
import org.topbraid.spin.util.SPINUtil;
import org.topbraid.spin.vocabulary.SP;
import org.topbraid.spin.vocabulary.SPIN;

import com.hp.hpl.jena.graph.Graph;
import com.hp.hpl.jena.graph.Node;
import com.hp.hpl.jena.graph.Triple;
import com.hp.hpl.jena.graph.compose.MultiUnion;
import com.hp.hpl.jena.query.Query;
import com.hp.hpl.jena.query.QueryExecution;
import com.hp.hpl.jena.query.QuerySolutionMap;
import com.hp.hpl.jena.query.Syntax;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.rdf.model.Property;
import com.hp.hpl.jena.rdf.model.RDFNode;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.rdf.model.Statement;
import com.hp.hpl.jena.rdf.model.StmtIterator;
import com.hp.hpl.jena.shared.ReificationStyle;
import com.hp.hpl.jena.sparql.core.Var;
import com.hp.hpl.jena.sparql.function.FunctionRegistry;
import com.hp.hpl.jena.sparql.syntax.Element;
import com.hp.hpl.jena.sparql.syntax.ElementGroup;
import com.hp.hpl.jena.sparql.syntax.ElementTriplesBlock;
import com.hp.hpl.jena.sparql.syntax.TemplateGroup;
import com.hp.hpl.jena.vocabulary.RDF;
import com.hp.hpl.jena.vocabulary.RDFS;


/**
 * Performs SPIN constraint checking on one or more instances, based
 * on the spin:constraints defined on the types of those instances.
 * 
 * @author Holger Knublauch
 */
public class SPINConstraints {
	
	private static List<TemplateCall> NO_FIXES = Collections.emptyList();
	

	private static void addConstraintViolations(List<ConstraintViolation> results, SPINInstance instance, Property predicate, boolean matchValue, List<SPINStatistics> stats, SPINModuleRegistry registry) {
		List<QueryOrTemplateCall> qots = instance.getQueriesAndTemplateCalls(predicate, registry);
		for(QueryOrTemplateCall qot : qots) {
			if(qot.getTemplateCall() != null) {
				addTemplateCallResults(results, qot, instance, matchValue, registry);					
			}
			else if(qot.getQuery() != null) {
				addQueryResults(results, qot, instance, matchValue, stats, registry);
			}
		}
	}


	/**
	 * Creates an RDF representation (instances of spin:ConstraintViolation) from a
	 * collection of ConstraintViolation Java objects. 
	 * @param cvs  the violation objects
	 * @param result  the Model to add the results to
	 * @param createSource  true to also create the spin:violationSource
	 */
	public static void addConstraintViolationsRDF(List<ConstraintViolation> cvs, Model result, boolean createSource) {
		for(ConstraintViolation cv : cvs) {
			Resource r = result.createResource(SPIN.ConstraintViolation);
			String message = cv.getMessage();
			if(message != null && message.length() > 0) {
				r.addProperty(RDFS.label, message);
			}
			if(cv.getRoot() != null) {
				r.addProperty(SPIN.violationRoot, cv.getRoot());
			}
			for(SimplePropertyPath path : cv.getPaths()) {
				if(path instanceof ObjectPropertyPath) {
					r.addProperty(SPIN.violationPath, path.getPredicate());
				}
				else {
					Resource p = result.createResource(SP.ReversePath);
					p.addProperty(SP.path, path.getPredicate());
					r.addProperty(SPIN.violationPath, p);
				}
			}
			if(createSource && cv.getSource() != null) {
				r.addProperty(SPIN.violationSource, cv.getSource());
			}
		}
	}

	
	private static void addConstructedProblemReports(
			Model cm, 
			List<ConstraintViolation> results, 
			Model model,
			Resource atClass,
			Resource matchRoot,
			String label,
			Resource source, SPINModuleRegistry registry) {
		StmtIterator it = cm.listStatements(null, RDF.type, SPIN.ConstraintViolation);
		while(it.hasNext()) {
			Statement s = it.nextStatement();
			Resource vio = s.getSubject();
			
			Resource root = null;
			Statement rootS = vio.getProperty(SPIN.violationRoot);
			if(rootS != null && rootS.getObject().isResource()) {
				root = (Resource)rootS.getResource().inModel(model);
			}
			if(matchRoot == null || matchRoot.equals(root)) {
				
				Statement labelS = vio.getProperty(RDFS.label);
				if(labelS != null && labelS.getObject().isLiteral()) {
					label = labelS.getString();
				}
				else if(label == null) {
					label = "SPIN constraint at " + SPINLabels.get().getLabel(atClass);
				}
				
				List<SimplePropertyPath> paths = getViolationPaths(model, vio, root);
				List<TemplateCall> fixes = getFixes(cm, model, vio, registry);
				results.add(createConstraintViolation(paths, fixes, root, label, source));
			}
		}
	}


	private static void addQueryResults(List<ConstraintViolation> results, QueryOrTemplateCall qot, Resource resource, boolean matchValue, List<SPINStatistics> stats, SPINModuleRegistry registry) {
		
		QuerySolutionMap arqBindings = new QuerySolutionMap();
		
		String queryString = ARQFactory.get().createCommandString(qot.getQuery(), registry);
		if(resource == null && SPINUtil.containsThis(qot.getQuery(), registry)) {
			queryString = SPINUtil.addThisTypeClause(queryString);
		}
		else {
			arqBindings.add(SPIN.THIS_VAR_NAME, resource);
		}
		
		Query arq = ARQFactory.get().createQuery(queryString);
		Model model = resource.getModel();
		QueryExecution qexec = ARQFactory.get().createQueryExecution(arq, model);
		
		qexec.setInitialBinding(arqBindings);
		
		long startTime = System.currentTimeMillis();
		if(arq.isAskType()) {
			if(qexec.execAsk() != matchValue) {
				String message;
				String comment = qot.getQuery().getComment();
				if(comment == null) {
					message = SPINLabels.get().getLabel(qot.getQuery());
				}
				else {
					message = comment;
				}
				message += "\n(SPIN constraint at " + SPINLabels.get().getLabel(qot.getCls()) + ")";
				List<SimplePropertyPath> paths = getPropertyPaths(resource, qot.getQuery().getWhere(), null, registry);
				Resource source = getSource(qot);
				results.add(createConstraintViolation(paths, NO_FIXES, resource, message, source));
			}
		}
		else if(arq.isConstructType()) {
			Model cm = qexec.execConstruct();
			qexec.close();
			addConstructedProblemReports(cm, results, model, qot.getCls(), resource, qot.getQuery().getComment(), getSource(qot), registry);
		}
		long endTime = System.currentTimeMillis();
		if(stats != null) {
			long duration = startTime - endTime;
			String label = qot.toString();
			String queryText;
			if(qot.getTemplateCall() != null) {
				queryText = SPINLabels.get().getLabel(qot.getTemplateCall().getTemplate(registry).getBody());
			}
			else {
				queryText = SPINLabels.get().getLabel(qot.getQuery());
			}
			Node cls = qot.getCls() != null ? qot.getCls().asNode() : null;
			stats.add(new SPINStatistics(label, queryText, duration, startTime, cls));
		}
	}


	private static void addTemplateCallResults(List<ConstraintViolation> results, QueryOrTemplateCall qot,
			Resource resource, boolean matchValue, SPINModuleRegistry registry) {
		TemplateCall templateCall = qot.getTemplateCall();
		Template template = templateCall.getTemplate(registry);
		if(template != null && template.getBody() instanceof org.topbraid.spin.model.Query) {
			org.topbraid.spin.model.Query spinQuery = (org.topbraid.spin.model.Query) template.getBody();
			if(spinQuery instanceof Ask || spinQuery instanceof Construct) {
				Model model = resource.getModel();
				Query arq = ARQFactory.get().createQuery(spinQuery, registry);
				QueryExecution qexec = ARQFactory.get().createQueryExecution(arq, model);
				setInitialBindings(resource, templateCall, qexec, registry);
				
				if(spinQuery instanceof Ask) {
					if(qexec.execAsk() != matchValue) {
						List<SimplePropertyPath> paths = getPropertyPaths(resource, spinQuery.getWhere(), templateCall.getArgumentsMapByProperties(registry), registry);
						String message = SPINLabels.get().getLabel(templateCall);
						message += "\n(SPIN constraint at " + SPINLabels.get().getLabel(qot.getCls()) + ")";
						results.add(createConstraintViolation(paths, NO_FIXES, resource, message, templateCall));
					}
				}
				else if(spinQuery instanceof Construct) {
					Model cm = qexec.execConstruct();
					qexec.close();
					Resource source = getSource(qot);
					String label = SPINLabels.get().getLabel(templateCall);
					addConstructedProblemReports(cm, results, model, qot.getCls(), resource, label, source, registry);
				}
			}
		}
	}

	
	/**
	 * Checks all spin:constraints for a given Resource.
	 * @param resource  the instance to run constraint checks on
	 * @param monitor  an (optional) progress monitor (currently ignored)
	 * @param registry TODO
	 * @param functionRegistry TODO
	 * @return a List of ConstraintViolations (empty if all is OK)
	 */
	public static List<ConstraintViolation> check(Resource resource, ProgressMonitor monitor, SPINModuleRegistry registry, FunctionRegistry functionRegistry) {
		return check(resource, new LinkedList<SPINStatistics>(), monitor, registry, functionRegistry);
	}
	
	
	/**
	 * Checks all spin:constraints for a given Resource.
	 * @param resource  the instance to run constraint checks on
	 * @param stats  an (optional) List to add statistics to
	 * @param monitor  an (optional) progress monitor (currently ignored)
	 * @param registry TODO
	 * @param functionRegistry TODO
	 * @return a List of ConstraintViolations (empty if all is OK)
	 */
	public static List<ConstraintViolation> check(Resource resource, List<SPINStatistics> stats, ProgressMonitor monitor, SPINModuleRegistry registry, FunctionRegistry functionRegistry) {
		List<ConstraintViolation> results = new LinkedList<ConstraintViolation>();
		
		// If spin:imports exist, then continue with the union model
		try {
			Model importsModel = SPINImports.get().getImportsModel(resource.getModel(), registry, functionRegistry);
			if(importsModel != resource.getModel()) {
				resource = ((Resource)resource.inModel(importsModel));
			}
		}
		catch(IOException ex) {
			ex.printStackTrace();
		}
		
		SPINInstance instance = resource.as(SPINInstance.class);
		addConstraintViolations(results, instance, SPIN.constraint, false, stats, registry);
		return results;
	}
	

	/**
	 * Checks all instances in a given Model against all spin:constraints and
	 * returns a List of constraint violations. 
	 * A ProgressMonitor can be provided to enable the user to get intermediate
	 * status reports and to cancel the operation.
	 * @param model  the Model to operate on
	 * @param monitor  an optional ProgressMonitor
	 * @param registry TODO
	 * @param functionRegistry TODO
	 * @return a List of ConstraintViolations
	 */
	public static List<ConstraintViolation> check(Model model, ProgressMonitor monitor, SPINModuleRegistry registry, FunctionRegistry functionRegistry) {
		return check(model, null, monitor, registry, functionRegistry);
	}
	

	/**
	 * Checks all instances in a given Model against all spin:constraints and
	 * returns a List of constraint violations. 
	 * A ProgressMonitor can be provided to enable the user to get intermediate
	 * status reports and to cancel the operation.
	 * @param model  the Model to operate on
	 * @param stats  an (optional) List to write statistics reports to
	 * @param monitor  an optional ProgressMonitor
	 * @param registry TODO
	 * @param functionRegistry TODO
	 * @return a List of ConstraintViolations
	 */
	public static List<ConstraintViolation> check(Model model, List<SPINStatistics> stats, ProgressMonitor monitor, SPINModuleRegistry registry, FunctionRegistry functionRegistry) {
		List<ConstraintViolation> results = new LinkedList<ConstraintViolation>();
		run(model, results, stats, monitor, registry, functionRegistry);
		return results;
	}
	
	
	private synchronized static Query convertAskToConstruct(Query ask, org.topbraid.spin.model.Query spinQuery, String label) {
		Syntax oldSyntax = Syntax.defaultSyntax; // Work-around to bug in ARQ
		try {
		    Syntax.defaultSyntax = ask.getSyntax();
			Query construct = com.hp.hpl.jena.query.QueryFactory.create(ask);
			construct.setQueryConstructType();
			TemplateGroup templates = new TemplateGroup();
			Node subject = Node.createAnon();
			templates.addTriple(Triple.create(subject, RDF.type.asNode(), SPIN.ConstraintViolation.asNode()));
			Node thisVar = Var.alloc(SPIN.THIS_VAR_NAME);
			templates.addTriple(Triple.create(subject, SPIN.violationRoot.asNode(), thisVar));
			if(label == null) {
				label = spinQuery.getComment();
			}
			if(label != null) {
				templates.addTriple(Triple.create(subject, RDFS.label.asNode(), Node.createLiteral(label)));
			}
			construct.setConstructTemplate(templates);
			Element where = construct.getQueryPattern();
			ElementGroup outerGroup = new ElementGroup();
			ElementTriplesBlock block = new ElementTriplesBlock();
			block.addTriple(Triple.create(thisVar, RDF.type.asNode(), Var.alloc(SPINUtil.TYPE_CLASS_VAR_NAME)));
			outerGroup.addElement(block);
			outerGroup.addElement(where);
			construct.setQueryPattern(outerGroup);
			return construct;
		}
		finally {
			Syntax.defaultSyntax = oldSyntax;
		}
	}


	private static ConstraintViolation createConstraintViolation(Collection<SimplePropertyPath> paths, Collection<TemplateCall> fixes, Resource instance, String message, Resource source) {
		return new ConstraintViolation(instance, paths, fixes, message, source);
	}


	private static List<TemplateCall> getFixes(Model cm, Model model, Resource vio, SPINModuleRegistry registry) {
		List<TemplateCall> fixes = new ArrayList<TemplateCall>();
		Iterator<Statement> fit = vio.listProperties(SPIN.fix);
		while(fit.hasNext()) {
			Statement fs = fit.next();
			if(fs.getObject().isResource()) {
				MultiUnion union = new MultiUnion(new Graph[] {
						model.getGraph(),
						cm.getGraph()
				});
				Model unionModel = ModelFactory.createModelForGraph(union);
				Resource r = (Resource) fs.getResource().inModel(unionModel);
				TemplateCall fix = SPINFactory.asTemplateCall(r, registry);
				fixes.add(fix);
			}
		}
		return fixes;
	}


	private static List<SimplePropertyPath> getPropertyPaths(Resource resource, ElementList where, Map<Property,RDFNode> varBindings, SPINModuleRegistry registry) {
		PropertyPathsGetter getter = new PropertyPathsGetter(where, varBindings, registry);
		getter.run();
		return new ArrayList<SimplePropertyPath>(getter.getResults()); 
	}
	
	
	private static Resource getSource(QueryOrTemplateCall qot) {
		if(qot.getQuery() != null) {
			return qot.getQuery();
		}
		else {
			return qot.getTemplateCall();
		}
	}


	private static List<SimplePropertyPath> getViolationPaths(Model model, Resource vio, Resource root) {
		List<SimplePropertyPath> paths = new ArrayList<SimplePropertyPath>();
		StmtIterator pit = vio.listProperties(SPIN.violationPath);
		while(pit.hasNext()) {
			Statement p = pit.nextStatement();
			if(p.getObject().isURIResource()) {
				Property predicate = model.getProperty(p.getResource().getURI());
				paths.add(new ObjectPropertyPath(root, predicate));
			}
			else if(p.getObject().isAnon()) {
				Resource path = p.getResource();
				if(path.hasProperty(RDF.type, SP.ReversePath)) {
					Statement reverse = path.getProperty(SP.path);
					if(reverse != null && reverse.getObject().isURIResource()) {
						Property predicate = model.getProperty(reverse.getResource().getURI());
						paths.add(new SubjectPropertyPath(root, predicate));
					}
				}
			}
		}
		return paths;
	}
	
	
	/**
	 * Checks if a given property is a SPIN constraint property.
	 * This is defined as a property that is spin:constraint or a sub-property of it.
	 * @param property  the property to check
	 * @return true if property is a constraint property
	 */
	public static boolean isConstraintProperty(Property property) {
		if(SPIN.constraint.equals(property)) {
			return true;
		}
		else if(JenaUtil.hasSuperProperty(property, property.getModel().getProperty(SPIN.constraint.getURI()))) {
			return true;
		}
		else {
			return false; 
		}
	}

	
	private static void run(Model model, List<ConstraintViolation> results, List<SPINStatistics> stats, ProgressMonitor monitor, SPINModuleRegistry registry, FunctionRegistry functionRegistry) {
		Map<CommandWrapper,Map<String,RDFNode>> templateBindings = new HashMap<CommandWrapper,Map<String,RDFNode>>();
		
		// If spin:imports exist then continue with the union model
		try {
			model = SPINImports.get().getImportsModel(model, registry, functionRegistry);
		}
		catch(IOException ex) {
			// TODO: better error handling
			ex.printStackTrace();
		}
		Map<Resource,List<CommandWrapper>> class2Query = SPINQueryFinder.getClass2QueryMap(model, model, SPIN.constraint, true, templateBindings, true, registry);
		for(Resource cls : class2Query.keySet()) {
			List<CommandWrapper> arqs = class2Query.get(cls);
			for(CommandWrapper arqWrapper : arqs) {
				QueryWrapper queryWrapper = (QueryWrapper) arqWrapper;
				Map<String,RDFNode> initialBindings = templateBindings.get(arqWrapper);
				Query arq = queryWrapper.getQuery();
				String label = arqWrapper.getLabel();
				if(arq.isAskType()) {
					arq = convertAskToConstruct(arq, queryWrapper.getSPINQuery(), label);
				}
				runQueryOnClass(results, arq, queryWrapper.getSPINQuery(), label, model, cls, initialBindings, arqWrapper.isThisUnbound(), arqWrapper.getSource(), stats, monitor, registry);
				if(!arqWrapper.isThisUnbound()) {
					Set<Resource> subClasses = JenaUtil.getAllSubClasses(cls);
					for(Resource subClass : subClasses) {
						runQueryOnClass(results, arq, queryWrapper.getSPINQuery(), label, model, subClass, initialBindings, arqWrapper.isThisUnbound(), arqWrapper.getSource(), stats, monitor, registry);
					}
				}
			}
		}
	}
	
	
	private static void runQueryOnClass(List<ConstraintViolation> results, Query arq, org.topbraid.spin.model.Query spinQuery, String label, Model model, Resource cls, Map<String,RDFNode> initialBindings, boolean thisUnbound, Resource source, List<SPINStatistics> stats, ProgressMonitor monitor, SPINModuleRegistry registry) {
		if(thisUnbound || model.contains(null, RDF.type, cls)) {
			QueryExecution qexec = ARQFactory.get().createQueryExecution(arq, model);
			QuerySolutionMap arqBindings = new QuerySolutionMap();
			if(!thisUnbound) {
				arqBindings.add(SPINUtil.TYPE_CLASS_VAR_NAME, cls);
			}
			if(initialBindings != null) {
				for(String varName : initialBindings.keySet()) {
					RDFNode value = initialBindings.get(varName);
					arqBindings.add(varName, value);
				}
			}
			qexec.setInitialBinding(arqBindings);
			
			if(monitor != null) {
				monitor.subTask("Checking SPIN constraint on " + SPINLabels.get().getLabel(cls));
			}
			
			long startTime = System.currentTimeMillis();
			Model cm = ModelFactory.createDefaultModel(ReificationStyle.Minimal);
			qexec.execConstruct(cm);
			qexec.close();
			long endTime = System.currentTimeMillis();
			if(stats != null) {
				long duration = endTime - startTime;
				String queryText = SPINLabels.get().getLabel(spinQuery);
				if(label == null) {
					label = queryText;
				}
				stats.add(new SPINStatistics(label, queryText, duration, startTime, cls.asNode()));
			}
			addConstructedProblemReports(cm, results, model, cls, null, label, source, registry);
		}
	}


	private static void setInitialBindings(Resource resource, TemplateCall templateCall,
			QueryExecution qexec, SPINModuleRegistry registry) {
		QuerySolutionMap arqBindings = new QuerySolutionMap();
		arqBindings.add(SPIN.THIS_VAR_NAME, resource);
		Map<Argument,RDFNode> args = templateCall.getArgumentsMap(registry);
		for(Argument arg : args.keySet()) {
			RDFNode value = args.get(arg);
			arqBindings.add(arg.getVarName(), value);
		}
		qexec.setInitialBinding(arqBindings);
	}
}