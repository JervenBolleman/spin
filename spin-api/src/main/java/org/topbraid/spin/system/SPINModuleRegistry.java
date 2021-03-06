/*******************************************************************************
 * Copyright (c) 2009 TopQuadrant, Inc.
 * All rights reserved. 
 *******************************************************************************/
package org.topbraid.spin.system;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.topbraid.spin.arq.EvalFunction;
import org.topbraid.spin.arq.SPINARQPFunction;
import org.topbraid.spin.arq.SPINFunctionDrivers;
import org.topbraid.spin.arq.SPINFunctionFactory;
import org.topbraid.spin.model.Function;
import org.topbraid.spin.model.SPINFactory;
import org.topbraid.spin.model.Template;
import org.topbraid.spin.util.JenaUtil;
import org.topbraid.spin.vocabulary.SPIN;
import org.topbraid.spin.vocabulary.SPL;

import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.sparql.function.FunctionFactory;
import com.hp.hpl.jena.sparql.function.FunctionRegistry;
import com.hp.hpl.jena.sparql.pfunction.PropertyFunctionFactory;
import com.hp.hpl.jena.sparql.pfunction.PropertyFunctionRegistry;
import com.hp.hpl.jena.vocabulary.RDF;


/**
 * A singleton that keeps track of all registered SPIN functions
 * and templates.  For example, in TopBraid this is populated by
 * walking all .spin. files in the Eclipse workspace.  Other
 * implementations may need to register their modules "manually".
 * 
 * @author Holger Knublauch
 */
public class SPINModuleRegistry {
	
	/**
	 * Remembers all function definitions (in their original Model) so that they
	 * can be retrieved later.
	 */
	private Map<String, Function> functions = new HashMap<String, Function>();
	
	/**
	 * Remembers the source objects (e.g. files) that a Function has been loaded from.
	 */
	private Map<Function, Set<Object>> sources = new HashMap<Function, Set<Object>>();
	
	/**
	 * Remembers all template definitions (in their original Model) so that they
	 * can be retrieved later.
	 */
	private Map<String, Template> templates = new HashMap<String, Template>();

	
	private static SPINModuleRegistry singleton = new SPINModuleRegistry();
	
	
	/**
	 * Gets the singleton instance of this class.
	 * @return the singleton
	 */
	public static SPINModuleRegistry get() {
		return singleton;
	}
	
	
	/**
	 * Sets the SPINModuleRegistry to another value.
	 * @param value  the new value (not null)
	 */
	public static void set(SPINModuleRegistry value) {
		singleton = value;
	}
	
	
	/**
	 * Gets a registered Function with a given URI.
	 * @param uri  the URI of the Function to get
	 * @param model  an (optional) Model that should also be used to look up
	 *               locally defined functions if they are not found in the registry
	 * @return the Function or null if none was found
	 */
	public Function getFunction(String uri, Model model) {
	    return getFunction(uri, model, Collections.emptySet());
	}
	
    /**
     * Gets a registered Function with a given URI.
     * @param uri  the URI of the Function to get
     * @param model  an (optional) Model that should also be used to look up
     *               locally defined functions if they are not found in the registry
     * @param validSources A set of objects corresponding to sources given to SPINModuleRegistry.registerAll, or null or an empty Set to include all available sources
     * @return the Function or null if none was found
     */
    public Function getFunction(String uri, Model model, Set<Object> validSources) {
	    Function function = functions.get(uri);
		if(function != null) 
		{
		    // include all functions if validSources was null or an empty Set
		    if(validSources == null || validSources.size() == 0)
		        return function;
		    else
		    {
		        Set<Object> validSet = sources.get(function);
		        for(Object nextSource : validSources)
		        {
		            if(validSet.contains(nextSource))
		            {
		                return function;
		            }
		        }
		    }
		}
		if(model != null) {
			function = model.getResource(uri).as(Function.class);
			if(JenaUtil.hasIndirectType(function, (Resource)SPIN.Function.inModel(model))) {
				return function;
			}
		}
		return null;
	}
	
	
	/**
	 * Gets a Collection of all registered Functions.
	 * @return the Templates
	 */
	public Collection<Function> getFunctions() {
		return functions.values();
	}


	/**
	 * Gets all Models that are associated to registered functions and templates.
	 * @return the Models
	 */
	public Set<Model> getModels() {
		Set<Model> spinModels = new HashSet<Model>();
		for(Function function : SPINModuleRegistry.get().getFunctions()) {
			spinModels.add(function.getModel());
		}
		for(Template template : SPINModuleRegistry.get().getTemplates()) {
			spinModels.add(template.getModel());
		}
		return spinModels;
	}

	
	public Set<Object> getSources(Function function) {
		return sources.get(function);
	}
	
    
    public Collection<Function> getFunctionsBySource(Object nextSource) {
        Collection<Function> results = new ArrayList<Function>();
        
        for(Function nextFunction : sources.keySet())
        {
            if(sources.get(nextFunction).contains(nextSource))
            {
                results.add(nextFunction);
            }
        }
        
        return results;
    }
    
	
	/**
	 * Gets a Template with a given URI in its defining Model.
	 * @param uri  the URI of the Template to look up
	 * @param model  an (optional) Model that should also be used for look up
	 * @return a Template or null
	 */
	public Template getTemplate(String uri, Model model) {
		if(model != null) {
			Resource r = model.getResource(uri);
			if(JenaUtil.hasIndirectType(r, (Resource)SPIN.Template.inModel(model))) {
				return r.as(Template.class);
			}
		}
		return templates.get(uri);
	}
	
	
	/**
	 * Gets a Collection of all registered Templates.
	 * @return the Templates
	 */
	public Collection<Template> getTemplates() {
		return templates.values();
	}
	
	
	/**
	 * Initializes this registry with all system functions and templates
	 * from the SPL namespace.
	 */
	public void init() {
	    // TODO: do these two registerAll calls work the same way as the previous one?
	    // Changed from one call to two to include the source for the functions from each of the two models
		Model splModel = SPL.getModel();
        registerAll(splModel, SPL.BASE_URI);
		Model spinModel = SPIN.getModel();
        registerAll(spinModel, SPIN.BASE_URI);

		FunctionRegistry.get().put(SPIN.eval.getURI(), new EvalFunction());
	}
	
	
	/**
	 * Registers a Function with its URI to this registry.
	 * As an optional side effect, if the provided function has a spin:body,
	 * this method can also register an ARQ FunctionFactory at the current
	 * Jena FunctionRegistry, using <code>registerARQFunction()</code>.
	 * <b>Note that the Model attached to the function should be an OntModel
	 * that also imports the system namespaces spin.owl and sp.owl - otherwise
	 * the system may not be able to transform the SPIN RDF into the correct
	 * SPARQL string.</b>
	 * @param function  the Function (must be a URI resource)
	 * @param source  an optional source for the function (e.g. a File)
	 * @param addARQFunction  true to also add an entry to the ARQ function registry
	 */
	public void register(Function function, Object source, boolean addARQFunction) {
		functions.put(function.getURI(), function);
		if(source != null) {
		    if(sources.containsKey(function))
		    {
		        sources.get(function).add(source);
		    }
		    else
		    {
		        Set<Object> newSet = new HashSet<Object>();
		        newSet.add(source);
		        sources.put(function, newSet);
		    }
		}
		ExtraPrefixes.add(function);
		if(addARQFunction) {
			registerARQFunction(function);
			if(function.hasProperty(RDF.type, SPIN.MagicProperty)) {
				registerARQPFunction(function);
			}
		}
	}
	
	
	/**
	 * Registers a Template with its URI.
	 * <b>Note that the Model attached to the template should be an OntModel
	 * that also imports the system namespaces spin.owl and sp.owl - otherwise
	 * the system may not be able to transform the SPIN RDF into the correct
	 * SPARQL string.</b>
	 * @param template  the Template (must be a URI resource)
	 */
	public void register(Template template) {
		templates.put(template.getURI(), template);
	}
	

	/**
	 * Registers all functions and templates from a given Model.
	 * <b>Note that the Model should contain the triples from the
	 * system namespaces spin.owl and sp.owl - otherwise the system
	 * may not be able to transform the SPIN RDF into the correct
	 * SPARQL string.  In a typical use case, the Model would be
	 * an OntModel that also imports the SPIN system namespaces.</b>
	 * @param model  the Model to iterate over
	 */
	public void registerAll(Model model, Object source) {
		registerFunctions(model, source);
		registerTemplates(model);
	}


	/**
	 * If the provided Function has an executable body (spin:body), then
	 * register an ARQ function for it with the current FunctionRegistry.
	 * If there is an existing function with the same URI already registered,
	 * then it will only be replaced if it is also a SPINARQFunction.
	 * @param spinFunction  the function to register
	 */
	protected void registerARQFunction(Function spinFunction) {
		FunctionFactory oldFF = FunctionRegistry.get().get(spinFunction.getURI());
		if(oldFF == null || oldFF instanceof SPINFunctionFactory) { // Never overwrite native Java functions
			SPINFunctionFactory newFF = SPINFunctionDrivers.get().create(spinFunction);
			if(newFF != null) {
				FunctionRegistry.get().put(spinFunction.getURI(), newFF);
			}
		}
	}


	/**
	 * If the provided Function has an executable body (spin:body), then
	 * register an ARQ function for it with the current FunctionRegistry. 
	 * If there is an existing function with the same URI already registered,
	 * then it will only be replaced if it is also a SPINARQPFunction.
	 * @param function  the function to register
	 */
	public void registerARQPFunction(Function function) {
		if(function.hasProperty(SPIN.body)) {
			PropertyFunctionFactory old = PropertyFunctionRegistry.get().get(function.getURI());
			if(old == null || old instanceof SPINARQPFunction) {
				SPINARQPFunction arqFunction = new SPINARQPFunction(function);
				PropertyFunctionRegistry.get().put(function.getURI(), arqFunction);
			}
		}
	}
	
	
	/**
	 * Registers all functions defined in a given Model.
	 * This basically iterates over all instances of spin:Function and calls
	 * <code>register(function)</code> for each of them.
	 * @param model  the Model to add the functions of
	 * @param source  an optional source of the Model
	 */
	public void registerFunctions(Model model, Object source) {
		for(Resource resource : JenaUtil.getAllInstances((Resource)SPIN.Function.inModel(model))) {
			Function function = SPINFactory.asFunction(resource);
			register(function, source, true);
		}
	}


	/**
	 * Registers all templates defined in a given Model.
	 * This basically iterates over all instances of spin:Template and calls
	 * <code>register(template)</code> for each of them.
	 * @param model  the Model to add the templates of
	 */
	public void registerTemplates(Model model) {
		for(Resource resource : JenaUtil.getAllInstances((Resource)SPIN.Template.inModel(model))) {
			if(resource.isURIResource()) {
				Template template = resource.as(Template.class);
				register(template);
				ExtraPrefixes.add(template);
			}
		}
	}
	
	
	/**
	 * Resets this registry, supporting things like server restarts.
	 */
	public void reset() {
		functions.clear();
		sources.clear();
		templates.clear();
	}
}
