/*
 * Copyright 2022-2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openmrs.android.fhir.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.liveData
import androidx.lifecycle.viewModelScope
import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.context.FhirVersionEnum
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.datacapture.mapping.ResourceMapper
import com.google.android.fhir.get
import org.openmrs.android.fhir.extensions.readFileFromAssets
import kotlinx.coroutines.launch
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Resource
import org.openmrs.android.fhir.FhirApplication
import org.openmrs.android.fhir.fragments.EditPatientFragment

/**
 * The ViewModel helper class for [EditPatientFragment], that is responsible for preparing data for
 * UI.
 */
class EditPatientViewModel(application: Application, private val state: SavedStateHandle) :
  AndroidViewModel(application) {
  private val fhirEngine: FhirEngine = FhirApplication.fhirEngine(application.applicationContext)

  private val patientId: String = requireNotNull(state["patient_id"])
  val livePatientData = liveData { emit(prepareEditPatient()) }

  private suspend fun prepareEditPatient(): Pair<String, String> {
    val patient = fhirEngine.get<Patient>(patientId)
    val launchContexts = mapOf<String, Resource>("client" to patient)
    val question =
      getApplication<Application>()
        .readFileFromAssets("new-patient-registration-paginated.json")
        .trimIndent()
    val parser = FhirContext.forCached(FhirVersionEnum.R4).newJsonParser()
    val questionnaire = parser.parseResource(Questionnaire::class.java, question) as Questionnaire

    val questionnaireResponse: QuestionnaireResponse =
      ResourceMapper.populate(questionnaire, launchContexts)
    val questionnaireResponseJson = parser.encodeResourceToString(questionnaireResponse)
    return question to questionnaireResponseJson
  }

  private val questionnaire: String
    get() = getQuestionnaireJson()

  val isPatientSaved = MutableLiveData<Boolean>()

  private val questionnaireResource: Questionnaire
    get() =
      FhirContext.forCached(FhirVersionEnum.R4).newJsonParser().parseResource(questionnaire)
        as Questionnaire

  private var questionnaireJson: String? = null

  /**
   * Update patient registration questionnaire response into the application database.
   *
   * @param questionnaireResponse patient registration questionnaire response
   */
  fun updatePatient(questionnaireResponse: QuestionnaireResponse) {
    viewModelScope.launch {
      val entry = ResourceMapper.extract(questionnaireResource, questionnaireResponse).entryFirstRep
      if (entry.resource !is Patient) return@launch
      val patient = entry.resource as Patient
      if (
        patient.hasName() &&
        patient.name[0].hasGiven() &&
        patient.name[0].hasFamily() &&
        patient.hasBirthDate() &&
        patient.hasTelecom() &&
        patient.telecom[0].value != null
      ) {
        patient.id = patientId
        fhirEngine.update(patient)
        isPatientSaved.value = true
        return@launch
      }

      isPatientSaved.value = false
    }
  }

  private fun getQuestionnaireJson(): String {
    questionnaireJson?.let {
      return it
    }
    questionnaireJson =
      getApplication<Application>()
        .readFileFromAssets(
          state[EditPatientFragment.QUESTIONNAIRE_FILE_PATH_KEY]!!,
        )
    return questionnaireJson!!
  }
}
