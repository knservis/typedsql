package com.rouesnel.typedsql

import com.twitter.scrooge.ThriftStruct

import scala.collection.immutable.ListMap
import scala.reflect.api.Trees
import scala.reflect.macros.whitebox
import com.rouesnel.typedsql.core._

import scalaz.{-\/, \/, \/-}

class SourceMapping(c: whitebox.Context) {
  import c.universe._

  def listMap[K, V](els: Seq[(K, V)]): ListMap[K, V] = ListMap(els: _*)

  private val seqType       = c.weakTypeOf[Seq[_]]
  private val mapType       = c.weakTypeOf[scala.collection.Map[_, _]]
  private val intType       = c.weakTypeOf[Int]
  private val longType      = c.weakTypeOf[scala.Long]
  private val javaLongType  = c.weakTypeOf[java.lang.Long]
  private val booleanType   = c.weakTypeOf[Boolean]
  private val doubleType    = c.weakTypeOf[Double]
  private val stringType    = c.weakTypeOf[String]
  private val dateType      = c.weakTypeOf[java.util.Date]
  private val shortType     = c.weakTypeOf[Short]
  private val byteType      = c.weakTypeOf[Byte]
  private val floatType     = c.weakTypeOf[Float]
  private val bigDecimalType = c.weakTypeOf[java.math.BigDecimal]
  private val thriftType    = c.weakTypeOf[ThriftStruct]

  /** Converts a Scala type to a Hive type */
  private def convertScalaToHiveType(tpe: Type): HiveType = tpe match {
    case typ if (typ <:< doubleType)       => DoubleType
    case typ if (typ <:< floatType)        => FloatType
    case typ if (typ <:< longType)         => LongType
    case typ if (typ <:< javaLongType)     => LongType
    case typ if (typ <:< intType)          => IntType
    case typ if (typ <:< shortType)        => ShortType
    case typ if (typ <:< byteType)         => TinyIntType
    case typ if (typ <:< booleanType)      => BooleanType
    case typ if (typ <:< dateType)         => DateType
    case typ if (typ <:< bigDecimalType)   => DecimalType(10, 0)
    case typ if (typ <:< stringType) => StringType
    case map if (map <:< mapType) => {
      val key :: value :: Nil = map.typeArgs
      MapType(convertScalaToHiveType(key), convertScalaToHiveType(key))
    }
    case seq if (seq <:< seqType) => {
      val inner = seq.typeArgs.head
      ArrayType(convertScalaToHiveType(inner))
    }
    case struct if (struct <:< thriftType) => {
      mapObjectTypeToHiveSchema(struct.companion)
    }
    case other => {
      c.abort(c.enclosingPosition, s"Could not convert scala type ${other} (${showRaw(other)}) to a Hive Type.")
    }
  }

  /** Converts a Scrooge struct type to a Hive type */
  private def mapObjectTypeToHiveSchema(thriftCompanion: Type): StructType = {
    // Now we can extract all relevant fields and reverse a schema.
    // 1. Extract the val {name}Field = new TField(...) methods
    val fieldNamesFromCompanion = thriftCompanion.members.toList.collect({
      case m: MethodSymbol if m.name.toString.endsWith("Field") && (! m.isPrivate) => {
        val fieldName = m.name.toString
        fieldName.substring(0, fieldName.length - "Field".length)
      }
    })

    // 2. Extract the reader fields to work out each return type.
    val readerMethodNames = fieldNamesFromCompanion.map(fieldName => {
      s"read${fieldName}Value" -> fieldName
    }).toMap
    val readerFields = thriftCompanion.members.toList.collect({
      case m: MethodSymbol if readerMethodNames.contains(m.name.toString) => {
        readerMethodNames(m.name.toString) -> m.returnType
      }
    })

    // 3. Perform case conversion.
    // Convert capitals to underscores unless followed by multipled capitals.
    val cleanedFields = readerFields.map({ case (name, fieldType) =>
      // Stolen from http://stackoverflow.com/a/1176023/49142
      val underscoredName =
        name
          .replaceAll("""(.)([A-Z][a-z]+)""", """$1_$2""")
          .replaceAll("""([a-z0-9])([A-Z])""", "$1_$2")
          .toLowerCase()

      underscoredName -> fieldType
    })

    StructType(listMap(cleanedFields.map({
      case (fieldName, fieldType) => fieldName -> convertScalaToHiveType(fieldType)
    })))
  }

  def readSources(parameters: Seq[Trees#Tree]): (Seq[Trees#Tree], Map[String, StructType]) = {
    val processed = parameters.collect({
      case q"$mods val ${name}: DataSource[${objectType}]" => {
        val typeChecked = c.typecheck(objectType, c.TYPEmode)
        val tpe = Option(typeChecked.tpe.companion).getOrElse(c.abort(c.enclosingPosition, "Could not determine type of " + objectType.toString()))
        // Ensure its a ThriftStructCodec3
        if (! tpe.weak_<:<(c.typecheck(tq"com.twitter.scrooge.ThriftStructCodec3[_]", c.TYPEmode).tpe)) {
          c.abort(c.enclosingPosition, s"${objectType} must be a subtype of ThriftStructCodec3[_]")
        }

        val cleanedFields = mapObjectTypeToHiveSchema(tpe)
        \/.right(name.toString -> cleanedFields)
      }
      case other => \/.left(other)
    })

    processed.collect({ case -\/(skipped) => skipped }) -> processed.collect({ case \/-(source) => source }).toMap

  }
}