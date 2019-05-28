package cromwell.backend.google.pipelines.common

import cats.data.Validated.{Invalid, Valid}
import common.validation.ErrorOr.ErrorOr
import cats.syntax.validated._
import cats.syntax.traverse._
import cats.instances.list._
import cats.syntax.apply._
import common.exception.AggregatedMessageException
import cromwell.core.{CromwellFatalExceptionMarker, WorkflowOptions}
import spray.json.{JsObject, JsString}

import scala.util.{Failure, Success, Try}
import scala.util.control.NoStackTrace

final case class GoogleLabel(key: String, value: String)

object GoogleLabels {

  val MaxLabelLength = 63
  val GoogleLabelKeyRegexPattern = "[a-z]([_-a-z0-9]*[a-z0-9])?"
  val GoogleLabelKeyRegex = GoogleLabelKeyRegexPattern.r
  val GoogleLabelValueRegexPattern = "[_-a-z0-9]*"
  val GoogleLabelValueRegex = GoogleLabelValueRegexPattern.r

  // This function is used to coerce a string into one that meets the requirements for a label submission to Google Pipelines API.
  // See 'labels' in https://cloud.google.com/genomics/reference/rpc/google.genomics.v1alpha2#google.genomics.v1alpha2.RunPipelineArgs
  def safeGoogleName(mainText: String, labelKey: Boolean): String = {

  val validator = if(labelKey) validateLabelKeyRegex(_) else validateLabelValueRegex(_)
    validator(mainText) match {
      case Valid(labelText) => labelText
      case invalid @ _ if mainText.equals("") && (!labelKey) => mainText
      case invalid @ _ =>
        def appendSafe(current: String, nextChar: Char): String = {
          nextChar match {
            case c if c.isLetterOrDigit || c == '-' => current + c.toLower
            case _ => current + '-'
          }
        }

        val foldResult = mainText.toCharArray.foldLeft("")(appendSafe)

        val startsValid = if (labelKey) foldResult.headOption.exists(_.isLetter) else foldResult.lastOption.exists(_.isLetterOrDigit)
        val endsValid = (
          foldResult.lastOption.exists(_.isLetterOrDigit)
          || foldResult.last.equals("-") || foldResult.last.equals("_")
        )

        val validStart = if (startsValid) foldResult else "x--" + foldResult
        val validStartAndEnd = if (endsValid) validStart else validStart + "--x"

        val length = validStartAndEnd.length
        val tooLong = length > MaxLabelLength

        if (tooLong) {
          val middleSeparator = "---"
          val subSectionLength = (MaxLabelLength - middleSeparator.length) / 2
          validStartAndEnd.substring(0, subSectionLength) + middleSeparator + validStartAndEnd.substring(length - subSectionLength, length)
        } else {
          validStartAndEnd
        }
    }
  }

  def validateLabelKeyRegex(s: String): ErrorOr[String] = {
    (GoogleLabelKeyRegex.pattern.matcher(s).matches, s.length <= MaxLabelLength) match {
      case (true, true) => s.validNel
      case (false, false) => s"Invalid label field: `$s` did not match regex '$GoogleLabelKeyRegexPattern' and it is ${s.length} characters. The maximum is $MaxLabelLength.".invalidNel
      case (false, _) => s"Invalid label field: `$s` did not match the regex '$GoogleLabelKeyRegexPattern'".invalidNel
      case (_, false) => s"Invalid label field: `$s` is ${s.length} characters. The maximum is $MaxLabelLength.".invalidNel
    }
  }

  def validateLabelValueRegex(s: String): ErrorOr[String] = {
    (GoogleLabelValueRegex.pattern.matcher(s).matches, s.length <= MaxLabelLength) match {
      case (true, true) => s.validNel
      case (false, false) => s"Invalid label field: `$s` did not match regex '$GoogleLabelValueRegexPattern' and it is ${s.length} characters. The maximum is $MaxLabelLength.".invalidNel
      case (false, _) => s"Invalid label field: `$s` did not match the regex '$GoogleLabelValueRegexPattern'".invalidNel
      case (_, false) => s"Invalid label field: `$s` is ${s.length} characters. The maximum is $MaxLabelLength.".invalidNel
    }
  }


  def safeLabels(values: (String, String)*): Seq[GoogleLabel] = {
    def safeGoogleLabel(kvp: (String, String)): GoogleLabel = {
      GoogleLabel(safeGoogleName(kvp._1, labelKey = true), safeGoogleName(kvp._2, labelKey = false))
    }
    values.map(safeGoogleLabel)
  }

  def validateLabel(key: String, value: String): ErrorOr[GoogleLabel] = {
    (validateLabelKeyRegex(key), validateLabelValueRegex(value)).mapN { (validKey, validValue) => GoogleLabel(validKey, validValue)  }
  }

  def fromWorkflowOptions(workflowOptions: WorkflowOptions): Try[Seq[GoogleLabel]] = {

    def extractGoogleLabelsFromJsObject(jsObject: JsObject): Try[Seq[GoogleLabel]] = {
      val asErrorOr = jsObject.fields.toList.traverse {
        case (key: String, value: JsString) => GoogleLabels.validateLabel(key, value.value)
        case (key, other) => s"Bad label value type for '$key'. Expected simple string but got $other".invalidNel : ErrorOr[GoogleLabel]
      }

      asErrorOr match {
        case Valid(value) => Success(value)
        case Invalid(errors) => Failure(new AggregatedMessageException("Invalid 'google_labels' in workflow options", errors.toList) with CromwellFatalExceptionMarker with NoStackTrace)
      }
    }

    workflowOptions.toMap.get("google_labels") match {
      case Some(obj: JsObject) => extractGoogleLabelsFromJsObject(obj)
      case Some(other) => Failure(new Exception(s"Invalid 'google_labels' in workflow options. Must be a simple JSON object mapping string keys to string values. Got $other") with NoStackTrace with CromwellFatalExceptionMarker)
      case None => Success(Seq.empty)
    }
  }

}
