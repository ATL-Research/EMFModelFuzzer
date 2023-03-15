package io.github.atlresearch.emfmodelfuzzer

import java.util.Collection
import java.util.HashMap
import java.util.Map
import org.eclipse.emf.ecore.EAttribute
import org.eclipse.emf.ecore.EEnumLiteral
import org.eclipse.emf.ecore.EObject
import org.eclipse.emf.ecore.resource.Resource

class SimpleEMFModelComparator {
    def boolean compare(Resource expected, Resource actual, StringBuffer message) {
        compare("/contents", expected.contents, actual.contents, new HashMap, message, [index, it | index])
    }
    
    private def dispatch String toDisplayString(Void o) {
    	"null"
    }

    private def dispatch String toDisplayString(Object o) {
    	o.toString
    }

    private def dispatch String toDisplayString(Collection<?> o) {
    	'''[«o.map[toDisplayString].join(", ")»]'''
    }

    def boolean compare(String path, Collection<?> c1, Collection<?> c2, Map<EObject, EObject> traversedElements, StringBuffer message, (Integer, Object)=>Object localIdGetter) {
        if(c1.size != c2.size) {
            message.append('''
            	Size difference at «path»: «c1.toDisplayString» != «c2.toDisplayString»
			''')
            return false
        }
        var ret = true
        var i = 0
/*
		// when order matters
        for(val i1 = c1.iterator, val i2 = c2.iterator ; i1.hasNext && i2.hasNext ; ) {
            val e1 = i1.next
            val e2 = i2.next
/*/
		// when order is not important
		val localIdToElement = new HashMap
		for(e2 : c2) {
			val localId = localIdGetter.apply(i++, e2)
			if(localIdToElement.containsKey(localId)) {
				 message.append('''
                	Local id «localId» is not unique at «path»: in «c2»
				''')
				return false
			}
			localIdToElement.put(localId, e2)
		}
		i = 0
        for(e1 : c1) {
        	val e2 = localIdToElement.get(localIdGetter.apply(i, e1))
/**/
            val newPath = '''«path»[«i»]'''
            if(e1 == null || e2 == null) {
                val r = e1 == e2
                ret = ret && r
                if(!r) {
                    message.append('''
                    	Existence difference at «newPath»: «e1.toDisplayString» != «e2.toDisplayString»
					''')
                }
            } else if(e1 instanceof EObject && e2 instanceof EObject) {
                ret = ret && compare(newPath, e1 as EObject, e2 as EObject, traversedElements, message, localIdGetter)
            } else {
                val r = e1.equals(e2)
                ret = ret && r
                if(!r) {
                    message.append('''
                    	Collection element equality difference at «newPath»: «e1.toDisplayString» != «e2.toDisplayString»
					''')
                }
            }
            i++
        }
        return ret
    }

    def boolean compare(String path, EObject eo1, EObject eo2, Map<EObject, EObject> traversedElements, StringBuffer message, (Integer, Object)=>Object localIdGetter) {
        if(traversedElements.containsKey(eo1)) {
            if(traversedElements.get(eo1) == eo2) {
            	return true
            } else {
            	message.append('''
            		EObject equality difference at «path»: «eo1.toDisplayString» already matched to «traversedElements.get(eo1).toDisplayString» != «eo2.toDisplayString»
            	''')
            	return false
            }
        }
        traversedElements.put(eo1, eo2)
        // We could compare eClasses if the metamodel was loaded only once
//		if(eo1.eClass() != eo2.eClass()) {
        if(!eo1.eClass.name.equals(eo2.eClass.name)) {
            message.append('''
            	Type difference at «path»: «eo1.toDisplayString» != «eo2.toDisplayString»
			''')
            return false
        }
        var ret = true
        for(feature : eo1.eClass.EAllStructuralFeatures) {
            val v1 = eo1.eGet(feature)
//          val v2 = eo2.eGet(feature)        // only works if the metamodel is loaded only once
            val v2 = eo2.eGet(eo2.eClass().getEStructuralFeature(feature.getName()));
            val newPath = '''«path»/«feature.name»'''
            if(v1 == null || v2 == null) {
                val r = v1 == v2
                ret = ret && r
                if(!r) {
                    message.append('''
                    	Existence difference at «newPath»: «v1.toDisplayString» != «v2.toDisplayString»
                    ''')
                }
            } else if(feature instanceof EAttribute) {
                val r =
	                if(v1 instanceof EEnumLiteral && v2 instanceof EEnumLiteral) {        // useless when metamodel is loaded only once
	                    (v1 as EEnumLiteral).literal.equals((v2 as EEnumLiteral).literal)
	                } else {
	                    v1.equals(v2)
	                }
                ret = ret && r
                if(!r) {
                    message.append('''
                    	Attribute value equality difference at «newPath»: «v1.toDisplayString» != «v2.toDisplayString»
					''')
                }
            } else if(feature.isMany()) {
                ret = ret && compare(newPath, v1 as Collection<?>, v2 as Collection<?>, traversedElements, message, localIdGetter)
            } else {
                ret = ret && compare(newPath, v1 as EObject, v2 as EObject, traversedElements, message, localIdGetter)
            }
        }
        return ret
    }
}
