package org.zkoss.bind.impl;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;


import org.zkoss.bind.BindContext;
import org.zkoss.bind.Binder;
import org.zkoss.bind.annotation.SelectorParam;
import org.zkoss.bind.annotation.ContextParam;
import org.zkoss.bind.annotation.ContextType;
import org.zkoss.bind.annotation.CookieParam;
import org.zkoss.bind.annotation.Default;
import org.zkoss.bind.annotation.ExecutionArgParam;
import org.zkoss.bind.annotation.ExecutionParam;
import org.zkoss.bind.annotation.HeaderParam;
import org.zkoss.bind.annotation.Param;
import org.zkoss.bind.annotation.QueryParam;
import org.zkoss.bind.annotation.Scope;
import org.zkoss.bind.annotation.ScopeParam;
import org.zkoss.lang.Classes;
import org.zkoss.util.logging.Log;
import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.Components;
import org.zkoss.zk.ui.Execution;
import org.zkoss.zk.ui.UiException;
import org.zkoss.zk.ui.select.Selectors;
/**
 * To help invoke a method with {@link Param} etc.. features.
 * @author dennis
 *
 */
public class ParamCall {

	private static final Log _log = Log.lookup(ParamCall.class);
	
	private Map<Class<? extends Annotation>, ParamResolver<Annotation>> _paramResolvers;
	private List<Type> _types;//to map class type directly, regardless the annotation
	private boolean _mappingType;//to enable the map class type without annotation, it is for compatible to rc2, only support BindeContext and Binder
	private ContextObjects _contextObjects;
	
	private static final String COOKIE_CACHE = "$PARAM_COOKIES$";
	
	private Component _root = null;
	private Component _component = null;
	private Execution _execution = null;
	private Binder _binder = null;
	private BindContext _bindContext = null;
	
	
	public ParamCall(){
		this(true);
	}
	public ParamCall(boolean mappingType){
		_paramResolvers = new HashMap<Class<? extends Annotation>, ParamResolver<Annotation>>();
		_contextObjects = new ContextObjects();
		_types = new ArrayList<Type>();
		_mappingType = mappingType;
		_paramResolvers.put(ContextParam.class, new ParamResolver<Annotation>() {
			@Override
			public Object resolveParameter(Annotation anno) {
				return _contextObjects.get(((ContextParam) anno).value());
			}
		});
	}
	
	public void setBindContext(BindContext ctx){
		_bindContext = ctx;
		_types.add(new Type(ctx.getClass(),_bindContext));
	}
	public void setBinder(Binder binder){
		_binder = binder;
		_types.add(new Type(binder.getClass(),_binder));
		_root = binder.getView();
	}
	
	public void setBindingArgs(final Map<String, Object> bindingArgs){
		_paramResolvers.put(Param.class, new ParamResolver<Annotation>() {
			@Override
			public Object resolveParameter(Annotation anno) {
				return bindingArgs.get(((Param) anno).value());
			}
		});
	}
	
	
	public void call(Object base, Method method) {
		Class<?>[] paramTypes = method.getParameterTypes();
		java.lang.annotation.Annotation[][] parmAnnos = method.getParameterAnnotations();
		Object[] params = new Object[paramTypes.length];

		try {
			for (int i = 0; i < paramTypes.length; i++) {
				params[i] = resolveParameter(parmAnnos[i],paramTypes[i]);
			}
			
			method.invoke(base, params);
		} catch (Exception e) {
			_log.error(e);
			throw UiException.Aide.wrap(e);
		}
	}
	
	private Object resolveParameter(java.lang.annotation.Annotation[] parmAnnos, Class<?> paramType){
		Object val = null;
		boolean hitResolver = false;
		Default defAnno = null;
		for(Annotation anno:parmAnnos){
			Class<?> annotype = anno.annotationType();
			
			if(defAnno==null && annotype.equals(Default.class)){
				defAnno = (Default)anno;
				continue;
			}
			ParamResolver<Annotation> resolver = _paramResolvers.get(annotype);
			if(resolver==null) continue;
			hitResolver = true;
			val = resolver.resolveParameter(anno);
			if(val!=null) {
				val = Classes.coerce(paramType, val);
				break;
			}
			//don't break until get a value
		}
		if(val == null && defAnno != null){
			val = Classes.coerce(paramType, defAnno.value());
		}
		
		//to compatible to rc2, do we have to?
		if(_mappingType && val==null && !hitResolver && _types!=null){
			for (Type type : _types) {
				if (type != null && paramType.isAssignableFrom(type.clz)) {
					val = type.value;
					break;
				}
			}
		}
		return val;
	}
	
	//utility to hold implicit class and runtime value
	private static class Type{
		final Class<?> clz;
		final Object value;
		public Type(Class<?> clz,Object value){
			this.clz = clz;
			this.value = value;
		}
	}
	
	private interface ParamResolver<T> {
		public Object resolveParameter(T anno);
	}

	
	public void setComponent(Component comp) {
		_component = comp;
		//scope param
		_paramResolvers.put(ScopeParam.class, new ParamResolver<Annotation>() {
			@Override
			public Object resolveParameter(Annotation anno) {
				final String name = ((ScopeParam)anno).value();
				final Scope[] ss = ((ScopeParam)anno).scopes();
				final List<Scope> scopes = new ArrayList<Scope>();  
				for(Scope s:ss){
					switch(s){
					case DEFAULT:
						scopes.addAll(Scope.getDefaultScopes());
						break;
					case ALL:
						scopes.addAll(Scope.getAllScopes());
						break;
					default:
						scopes.add(s);
					}
				}
				Object val = null;
				for(Scope scope:scopes){
					final String scopeName = scope.getName();
					Object scopeObj = Components.getImplicit(_component, scopeName);
					if(scopeObj instanceof Map){
						val = ((Map<?,?>)scopeObj).get(name);
						if(val!=null) break;
					}else if(scopeObj !=null){
						_log.error("the scope of "+scopeName+" is not a Map, is "+scopeObj);
					}
				}
				return val;
			}
		});
		
		//component
		_paramResolvers.put(SelectorParam.class, new ParamResolver<Annotation>() {
			@Override
			public Object resolveParameter(Annotation anno) {
				final String selector = ((SelectorParam) anno).value();
				final boolean local =  ((SelectorParam) anno).local();
				final int index =  ((SelectorParam) anno).index();
				if(!local && _root==null){
					return null;
				}
				if(index<0){
					return Selectors.find(local?_component:_root, selector);
				}else{
					return Selectors.find(local?_component:_root, selector, index);
				}
			}
		});
	}
	public void setExecution(Execution exec) {
		_execution = exec;
		//http param
		_paramResolvers.put(QueryParam.class, new ParamResolver<Annotation>() {
			@Override
			public Object resolveParameter(Annotation anno) {
				return _execution.getParameter(((QueryParam) anno).value());
			}
		});
		_paramResolvers.put(HeaderParam.class, new ParamResolver<Annotation>() {
			@Override
			public Object resolveParameter(Annotation anno) {
				return _execution.getHeader(((HeaderParam) anno).value());
			}
		});
		_paramResolvers.put(CookieParam.class, new ParamResolver<Annotation>() {
			@Override
			@SuppressWarnings("unchecked")
			public Object resolveParameter(Annotation anno) {
				Map<String,Object> m = (Map<String,Object>)_execution.getAttribute(COOKIE_CACHE);
				if(m==null){
					final Object req  = _execution.getNativeRequest();
					m = new HashMap<String,Object>();
					_execution.setAttribute(COOKIE_CACHE, m);
					
					if(req instanceof HttpServletRequest){
						final Cookie[] cks = ((HttpServletRequest)req).getCookies();
						if(cks != null){
							for(Cookie ck:cks){
								m.put(ck.getName().toLowerCase(), ck.getValue());
							}
						}
					}else/* if(req instanceof PortletRequest)*/{
						//no cookie in protlet 1.0
					}
				}
				return m==null?null:m.get(((CookieParam) anno).value().toLowerCase());
			}
		});

		//execution
		_paramResolvers.put(ExecutionParam.class, new ParamResolver<Annotation>() {
			@Override
			public Object resolveParameter(Annotation anno) {
				return _execution.getAttribute(((ExecutionParam) anno).value());
			}
		});
		
		_paramResolvers.put(ExecutionArgParam.class, new ParamResolver<Annotation>() {
			@Override
			public Object resolveParameter(Annotation anno) {
				return _execution.getArg().get(((ExecutionArgParam) anno).value());
			}
		});
	}
	
	class ContextObjects {
		public Object get(ContextType type){
			switch(type){
			//bind contexts
			case BIND_CONTEXT:
				return _bindContext;
			case BINDER:
				return _binder;
			
			//zk execution contexts
			case EXECUTION:
				return _execution;
			case COMPONENT:
				return _component;
			case SPACE_OWNER:
				return _component==null?null:_component.getSpaceOwner();
			case PAGE:
				return _component==null?null:_component.getPage();
			case DESKTOP:
				return _component==null?null:_component.getDesktop();
			case SESSION:
				return _component==null?null:Components.getImplicit(_component, "session");
			case APPLICATION:
				return _component==null?null:Components.getImplicit(_component, "application");
				
			//zk execution scope contexts
			case REQUEST_SCOPE:
				return _component==null?null:Components.getImplicit(_component, "requestScope");
			case COMPONENT_SCOPE:
				return _component==null?null:Components.getImplicit(_component, "componentScope");
			case SPACE_SCOPE:
				return _component==null?null:Components.getImplicit(_component, "spaceScope");
			case PAGE_SCOPE:
				return _component==null?null:Components.getImplicit(_component, "pageScope");
			case DESKTOP_SCOPE:
				return _component==null?null:Components.getImplicit(_component, "desktopScope");
			case SESSION_SCOPE:
				return _component==null?null:Components.getImplicit(_component, "sessionScope");
			case APPLICATION_SCOPE:
				return _component==null?null:Components.getImplicit(_component, "applicationScope");
			}
			return null;
		}
	}
}