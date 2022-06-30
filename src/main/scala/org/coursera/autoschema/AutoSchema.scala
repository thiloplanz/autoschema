/**
 *  Copyright 2014 Coursera Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.coursera.autoschema

import play.api.libs.json.JsArray
import play.api.libs.json.Json
import play.api.libs.json.JsObject
import play.api.libs.json.JsString

import scala.collection.mutable
import scala.reflect.runtime.{universe => ru}

abstract class AutoSchema {
  _: TypeMappings =>

  private[this] val classSchemaCache = collection.concurrent.TrieMap[String, JsObject]()

  private def isOfType(annotation : ru.Annotation, tpe : String) = annotation.tree.tpe.typeSymbol.fullName == tpe

  private[this] val isHideAnnotation = (annotation: ru.Annotation) => isOfType(annotation, "org.coursera.autoschema.annotations.Term.Hide")

  private[this] val isFormatAnnotation = (annotation: ru.Annotation) => isOfType(annotation,  "org.coursera.autoschema.annotations.FormatAs")

  private[this] val isExposeAnnotation = (annotation: ru.Annotation) => isOfType(annotation, "org.coursera.autoschema.annotations.ExposeAs")

  private[this] val isTermExposeAnnotation = (annotation: ru.Annotation) => isOfType(annotation, "org.coursera.autoschema.annotations.Term.ExposeAs")

  private[this] val isDescriptionAnnotation = (annotation: ru.Annotation) => isOfType(annotation, "org.coursera.autoschema.annotations.Description")

  // Generates JSON schema based on a FormatAs annotation
  private[this] def formatAnnotationJson(annotation: ru.Annotation) = {
    annotation.tree.children.tail match {
      case typ :: Nil =>
        Json.obj("type" -> typ.toString().tail.init)
      case typ :: format :: Nil =>
        Json.obj("type" -> typ.toString().tail.init, "format" -> format.toString().tail.init)
      case x =>
        Json.obj()
    }
  }

  private [this] def descriptionAnnotationJson(annotation: ru.Annotation) = {
    annotation.tree.children.tail match {
      case description :: Nil =>
        Some("description" -> JsString(description.toString().tail.init))
      case _ => None
    }
  }

  private[this] def createClassJson(tpe: ru.Type, previousTypes: Set[String]) = {
    // Check if schema for this class has already been generated
    classSchemaCache.getOrElseUpdate(tpe.typeSymbol.fullName, {
      val title = tpe.typeSymbol.name.decodedName.toString
      var requiredValues = Seq[String]()
      val propertiesList = tpe.members.flatMap { member =>
        if (member.isTerm) {
          val term = member.asTerm
          if ((term.isVal || term.isVar) && !term.annotations.exists(isHideAnnotation)) {
            val termFormat = term.annotations.find(isFormatAnnotation)
              .map(formatAnnotationJson)
              .getOrElse {
              term.annotations.find(isTermExposeAnnotation)
                .map(annotation =>
                createSchema(annotation.tree.tpe.asInstanceOf[ru.TypeRefApi].args.head, previousTypes))
                .getOrElse(createSchema(term.typeSignature, previousTypes + tpe.typeSymbol.fullName))
            }

            //If it is not an `Option`, it is required.
            if(term.typeSignature.typeSymbol.fullName != "scala.Option")
              requiredValues = requiredValues ++ Seq(term.name.decodedName.toString.trim)

            val description = term.annotations.find(isDescriptionAnnotation).flatMap(descriptionAnnotationJson)
            val termFormatWithDescription = description match  {
              case Some(value) => termFormat + value
              case None => termFormat
            }

            Some(term.name.decodedName.toString.trim -> termFormatWithDescription)
          } else {
            None
          }
        } else {
          None
        }
      }.toList.sortBy(_._1)

      val properties = JsObject(propertiesList)

      // Return the value and add it to the cache (since we're using getOrElseUpdate
      Json.obj("title" -> title, "type" -> "object", "required" -> JsArray(requiredValues.map(JsString)), "properties" -> properties)
    })
  }

  private[this] def extendsValue(tpe: ru.Type) = {
    tpe.baseClasses.exists(_.fullName == "scala.Enumeration.Value")
  }

  private[this] def addDescription[T](tpe: ru.Type, obj: JsObject): JsObject = {
    val description = tpe.typeSymbol.annotations.find(isDescriptionAnnotation).flatMap(descriptionAnnotationJson)
    description match {
      case Some(descr) => obj + descr
      case None => obj
    }
  }

  private val hardcodedListOfScalaCollectionTypes = Set(
    "scala.collection.Traversable",
    "scala.Array",
    "scala.Seq",
    "scala.List",
    "scala.Vector",
    "scala.collection.immutable.Seq",
    "scala.collection.mutable.Seq",
    "scala.collection.immutable.List",
    "scala.collection.immutable.Vector"
  )

  private[this] def createSchema(tpe: ru.Type, previousTypes: Set[String]): JsObject = {
    val typeName = tpe.typeSymbol.fullName

    if (extendsValue(tpe)) {
      val mirror = ru.runtimeMirror(getClass.getClassLoader)
      val enumName = tpe.toString.split('.').init.mkString(".")
      val module = mirror.staticModule(enumName)
      val `enum` = mirror.reflectModule(module).instance.asInstanceOf[Enumeration]
      val options = `enum`.values.toList.map { v =>
        Json.toJson(v.toString)
      }

      val optionsArr = JsArray(options)
      val enumJson = Json.obj(
        "type" -> "string",
        "enum" -> optionsArr
      )
      addDescription(tpe, enumJson)

    } else if (typeName == "scala.Option") {
      // Option[T] becomes the schema of T with required set to false
      val jsonOption = createSchema(tpe.asInstanceOf[ru.TypeRefApi].args.head, previousTypes)
      addDescription(tpe, jsonOption)
    } else if (tpe.baseClasses.exists(s => hardcodedListOfScalaCollectionTypes.contains(s.fullName))) {
      // (Traversable)[T] becomes a schema with items set to the schema of T
      val jsonSeq = Json.obj("type" -> "array", "items" -> createSchema(tpe.asInstanceOf[ru.TypeRefApi].args.head, previousTypes))
      addDescription(tpe, jsonSeq)
    } else {
      val jsonObj = tpe.typeSymbol.annotations.find(isFormatAnnotation)
        .map(formatAnnotationJson)
        .getOrElse {
        tpe.typeSymbol.annotations.find(isExposeAnnotation)
          .map(annotation => createSchema(annotation.tree.tpe.asInstanceOf[ru.TypeRefApi].args.head, previousTypes))
          .getOrElse {
          schemaTypeForScala(typeName).getOrElse {
            if (tpe.typeSymbol.isClass) {
              // Check if this schema is recursive
              if (previousTypes.contains(tpe.typeSymbol.fullName)) {
                throw new IllegalArgumentException(s"Recursive types detected: $typeName")
              }

              createClassJson(tpe, previousTypes)
            } else {
              Json.obj()
            }
          }
        }
      }
      addDescription(tpe, jsonObj)
    }
  }

  /**
   * Create schema based on reflection type
   * @param tpe
   * The reflection type to be converted into JSON Schema
   * @return
   * The JSON Schema for the type as a JsObject
   */
  def createSchema(tpe: ru.Type): JsObject = createSchema(tpe, Set.empty)

  /**
   *
   * @tparam T
   * The type to be converted into JSON Schema
   * @return
   * The JSON Schema for the type as a JsObject
   */
  def createSchema[T: ru.TypeTag]: JsObject = createSchema(ru.typeOf[T])

  /**
   * Create a schema and format it according to the style
   * @param tpe The reflection type to be converted into JSON Schema
   * @param indent The left margin indent in pixels
   * @return The JSON Schema for the type as a formatted string
   */
  def createPrettySchema(tpe: ru.Type, indent: Int): String =
    styleSchema(Json.prettyPrint(createSchema(tpe)), indent)

  /**
   * Create a schema and format it according to the style
   * @param indent The left margin indent in pixels
   * @return The JSON Schema for the type as a formatted string
   */
  def createPrettySchema[T: ru.TypeTag](indent: Int): String =
    styleSchema(Json.prettyPrint(createSchema(ru.typeOf[T])), indent)

  private[this] def styleSchema(schema: String, indent: Int) =
    s"""<div style="margin-left: ${indent}px; background-color: #E8E8E8; border-width: 1px;"><i>$schema</i></div>"""
}

/**
 * AutoSchema lets you take any Scala type and create JSON Schema out of it
 * @example
 * {{{
 *      // Pass the type as a type parameter
 *      case class MyType(...)
 *
 *      AutoSchema.createSchema[MyType]
 *
 *
 *      // Or pass the reflection type
 *      case class MyOtherType(...)
 *
 *      AutoSchema.createSchema(ru.typeOf[MyOtherType])
 * }}}
 */
object AutoSchema extends AutoSchema with DefaultTypeMappings
