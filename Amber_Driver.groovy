/**
 *	Amber Electric Integration for Hubitat
 *	Author: Derek Osborn (dJOS)
 *
 *	Licensed under the GNU General Public License v2.0
 *
 *	Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *	on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *	for the specific language governing permissions and limitations under the License.
 *
 *	v1.0.0 - Original Release
 *	v1.1.0 - Fix Scheduling Bug
 *	v1.2.0 - Added Rounding of pricing data to 2 decimal places
 *	v1.3.0 - Added a HTML Dashboard tile with Import / Export Prices
 *	v1.3.1 - Bug fix for HTML Tile and modified Poll Scheduling
 */

import groovy.json.JsonSlurper
import groovy.transform.Field

static String version() {
    return "1.3.0"
}

static String siteApi() {
    return "https://api.amber.com.au/v1/sites" // Sites API url
}

preferences {
    input name: "about", type: "paragraph", element: "paragraph", title: "Amber Electric Integration", description: "v.${version()}"
    input("pollingInterval", "enum", title: "Polling Interval (minutes)",
          options: ["5", "10", "15", "30", "60", "180"],
          defaultValue: "5", required: true)
    input("apiKey", "text", title: "API Key", required: true)
    input("debugEnable", "bool", title: "Enable debug logging?", defaultValue: true)
}

metadata {
    definition(name: "Amber Electric Integration for Hubitat", namespace: "dJOS", author: "dJOS",importUrl: "https://raw.githubusercontent.com/dJOS1475/Amber-Electric---Hubitat-Driver/main/Amber_Driver.groovy") {
        capability "Refresh"
        capability "Polling"
        attribute "siteId", "string"
        attribute "nmi", "string"
        attribute "network", "string"
        attribute "status", "string"
        attribute "activeFrom", "date"
        attribute "currentIntervalPerKwh", "number"
        attribute "currentIntervalRenewables", "number"
        attribute "currentIntervalSpotPerKwh", "number"
        attribute "currentIntervalChannelType", "string"
        attribute "currentIntervalSpikeStatus", "string"
        attribute "currentIntervalDescriptor", "string"
        attribute "currentIntervalEstimate", "bool"
        attribute "htmlPrices", "string"
        command "refresh"
    }
}

void poll() {
    pullData()
}

void refresh() {
    pullData()
}

void updated() {
	if (!state.updatedLastRanAt || now() >= state.updatedLastRanAt + 2000) {
		state.updatedLastRanAt = now()
		trace "updated() called with settings: ${settings.inspect()}".toString()
        
		pullData()
		startPoll()
        if(debugEnable) runIn(1800,logsOff)
	} else {
		trace "updated() ran within the last 2 seconds - skipping"
	}
}

void logsOff() {
	debug "debug logging disabled..."
	device.updateSetting("debugEnable",[value:"false",type:"bool"])
}

void startPoll() {
	unschedule()
	// Schedule polling based on preference setting
	def sec = Math.round(Math.floor(Math.random() * 60))
	def min = Math.round(Math.floor(Math.random() * settings.pollingInterval.toInteger()))
	String cron = "${sec} ${min}/${settings.pollingInterval.toInteger()} * * * ?" // every N min
	trace "startPoll: schedule('$cron', pullData)".toString()
	schedule(cron, pullData)
}


void pullData() {
    log.info "Requesting data from Amber API"

    try {
        // Prepare request parameters
        def requestParams = [
            uri: siteApi(),
            contentType: "application/json",
            headers: [
                Authorization: "Bearer ${apiKey}" // Use the provided API key
            ]
        ]

        // Make API request
        httpGet(requestParams) { resp ->
            def response = resp
            if (response.status == 200) {
                def responseData = response.data

                // Process response data
                processResponse(responseData)
            } else {
                debug "Error: HTTP status ${response.status}"
            }
        }
    } catch (Exception e) {
        log.warn "Error occurred: ${e.message}"
    }
    pauseExecution(1000)
    currentPrices() // Update htmlPrices dashboard tile
}

void processResponse(data) {
    if (data instanceof List && !data.isEmpty()) {
        def site = data[0]
        sendEvent(name: "siteId", value: site.id)
        sendEvent(name: "nmi", value: site.nmi)
        sendEvent(name: "network", value: site.network)
        sendEvent(name: "status", value: site.status)
        sendEvent(name: "activeFrom", value: parseDate(site.activeFrom))

        // Make additional request for current interval data
        def siteId = site.id
        def currentIntervalApi = "https://api.amber.com.au/v1/sites/${siteId}/prices/current"
        def requestParams = [
            uri: currentIntervalApi,
            contentType: "application/json",
            headers: [
                Authorization: "Bearer ${apiKey}" // Use the provided API key
            ]
        ]
        httpGet(requestParams) { resp ->
            def response = resp
            if (response.status == 200) {
                def responseData = response.data

                // Process current interval response data
                processCurrentIntervalResponse(responseData)
            } else {
                debug "Error: HTTP status ${response.status}"
            }
        }
    } else {
        log.warn "No data found in the response."
    }
}

void processCurrentIntervalResponse(data) {
    if (data instanceof List && !data.isEmpty()) {
        def currentIntervalData = data[0]
        BigDecimal roundedPerKwh = currentIntervalData.perKwh?.setScale(2, BigDecimal.ROUND_HALF_UP)
        BigDecimal roundedRenewables = currentIntervalData.renewables?.setScale(2, BigDecimal.ROUND_HALF_UP)
        BigDecimal roundedSpotPerKwh = currentIntervalData.spotPerKwh?.setScale(2, BigDecimal.ROUND_HALF_UP)
        sendEvent(name: "currentIntervalPerKwh", value: roundedPerKwh)
        sendEvent(name: "currentIntervalRenewables", value: roundedRenewables)
        sendEvent(name: "currentIntervalSpotPerKwh", value: roundedSpotPerKwh)
        sendEvent(name: "currentIntervalChannelType", value: currentIntervalData.channelType)
        sendEvent(name: "currentIntervalSpikeStatus", value: currentIntervalData.spikeStatus)
        sendEvent(name: "currentIntervalDescriptor", value: currentIntervalData.descriptor)
        sendEvent(name: "currentIntervalEstimate", value: currentIntervalData.estimate)
    } else {
        log.warn "No current interval data found in the response."
    }
}

def currentPrices() {
	if(txtEnable == true){log.debug "updateTile1 called"}		// log the data returned by AE//	
	htmlPrices ="<div style='line-height:1.0; font-size:0.75em;'><br>Import Price:<br></div>"
    htmlPrices +="<div style='line-height:1.0; font-size:0.75em;'><br>${device.currentValue('currentIntervalPerKwh')}c per KwH<br></div>"
	htmlPrices +="<div style='line-height:50%;'><br></div>"
    htmlPrices +="<div style='line-height:1.0; font-size:0.75em;'><br>Export Price: <br></div>"
    htmlPrices +="<div style='line-height:1.0; font-size:0.75em;'><br>${device.currentValue('currentIntervalSpotPerKwh')}c per KwH<br></div>"
	sendEvent(name: "htmlPrices", value: htmlPrices)
	if(txtEnable == true){log.debug "htmlPrices contains ${htmlPrices}"}		// log the data returned by AE//	
	if(txtEnable == true){log.debug "${htmlPrices.length()}"}		// log the data returned by AE//	
	}

Date parseDate(String dateString) {
    try {
        return Date.parse("yyyy-MM-dd", dateString)
    } catch (Exception e) {
        log.warn "Error parsing date: ${e.message}"
        return null
    }
}

void debug(String msg) {
    if (debugEnable) log.debug "${device.displayName} - ${msg}"
}

void trace(String msg) {
	if(debugEnable) log.trace device.displayName+' - '+msg
}

