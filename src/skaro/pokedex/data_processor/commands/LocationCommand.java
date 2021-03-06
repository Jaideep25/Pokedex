package skaro.pokedex.data_processor.commands;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import discord4j.core.object.entity.User;
import discord4j.core.spec.EmbedCreateSpec;
import reactor.core.publisher.Mono;
import skaro.pokedex.data_processor.PokedexCommand;
import skaro.pokedex.data_processor.IDiscordFormatter;
import skaro.pokedex.data_processor.Response;
import skaro.pokedex.data_processor.TextUtility;
import skaro.pokedex.input_processor.CommandArgument;
import skaro.pokedex.input_processor.Input;
import skaro.pokedex.input_processor.Language;
import skaro.pokedex.input_processor.arguments.ArgumentCategory;
import skaro.pokedex.services.ColorService;
import skaro.pokedex.services.IServiceManager;
import skaro.pokedex.services.ServiceConsumerException;
import skaro.pokedex.services.ServiceException;
import skaro.pokedex.services.ServiceType;
import skaro.pokeflex.api.Endpoint;
import skaro.pokeflex.api.PokeFlexFactory;
import skaro.pokeflex.objects.encounter.ConditionValue;
import skaro.pokeflex.objects.encounter.Encounter;
import skaro.pokeflex.objects.encounter.EncounterDetail;
import skaro.pokeflex.objects.encounter.EncounterPotential;
import skaro.pokeflex.objects.encounter.VersionDetail;
import skaro.pokeflex.objects.pokemon.Pokemon;

public class LocationCommand extends PokedexCommand 
{
	public LocationCommand(IServiceManager services, IDiscordFormatter formatter) throws ServiceConsumerException
	{
		super(services, formatter);
		if(!hasExpectedServices(this.services))
			throw new ServiceConsumerException("Did not receive all necessary services");
		
		commandName = "location".intern();
		orderedArgumentCategories.add(ArgumentCategory.POKEMON);
		orderedArgumentCategories.add(ArgumentCategory.VERSION);
		expectedArgRange = new ArgumentRange(2,2);
		aliases.put("loc", Language.ENGLISH);
		
		createHelpMessage("fearow, blue", "Abra, Soul Silver", "Ditto, Yellow", "trubbish, black 2",
				"https://i.imgur.com/CkPBiDT.gif");
	}
	
	@Override
	public boolean makesWebRequest() { return true; }
	@Override
	public String getArguments() { return "<pokemon>, <version>"; }
	
	@Override
	public boolean hasExpectedServices(IServiceManager services) 
	{
		return super.hasExpectedServices(services) &&
				services.hasServices(ServiceType.POKE_FLEX, ServiceType.PERK, ServiceType.COLOR);
	}
	
	@Override
	public boolean inputIsValid(Response reply, Input input)
	{
		if(!input.isValid())
		{
			switch(input.getError())
			{
				case ARGUMENT_NUMBER:
					reply.addToReply("You must specify a Pokemon and a Version as input for this command "
							+ "(seperated by commas).");
				break;
				case INVALID_ARGUMENT:
					reply.addToReply("Could not process your request due to the following problem(s):".intern());
					for(CommandArgument arg : input.getArguments())
						if(!arg.isValid())
							reply.addToReply("\t\""+arg.getRawInput()+"\" is not a recognized "+ arg.getCategory());
					reply.addToReply("\n*top suggestion*: Not updated for gen7. Try versions from gens 1-6?");
				break;
				default:
					reply.addToReply("A technical error occured (code 111)");
			}
			
			return false;
		}
		
		return true;
	}
	
	@Override
	public Mono<Response> discordReply(Input input, User requester)
	{ 
		Response reply = new Response();
		
		//Check if input is valid
		if(!inputIsValid(reply, input))
			return Mono.just(reply);
		
		Pokemon pokemon = null;
		Encounter encounterData = null;
		PokeFlexFactory factory;
		List<String> urlParams = new ArrayList<String>();
		
		try 
		{
			factory = (PokeFlexFactory)services.getService(ServiceType.POKE_FLEX);
			
			//Obtain Pokemon data
			urlParams.add(input.getArgument(0).getFlexForm());
			Object flexObj = factory.createFlexObject(Endpoint.POKEMON, urlParams);
			pokemon = Pokemon.class.cast(flexObj);
			
			//Extract needed data from the Pokemon data and get Encounter data
			urlParams.clear();
			urlParams.add(Integer.toString(pokemon.getId()));
			urlParams.add("encounters");
			flexObj = factory.createFlexObject(Endpoint.ENCOUNTER, urlParams);
			encounterData = Encounter.class.cast(flexObj);
			
			//Get encounter data from particular version
			String versionDBForm = input.getArgument(1).getDbForm();
			List<EncounterPotential> encounterDataFromVersion = getEncounterDataFromVersion(encounterData, versionDBForm);
			
			if(encounterDataFromVersion.isEmpty())
			{
				reply.addToReply(TextUtility.flexFormToProper(pokemon.getName())+" cannot be found by means of a normal encounter in "
						+ TextUtility.flexFormToProper(input.getArgument(1).getRawInput())+" version");
				return Mono.just(reply);
			}
			
			reply.addToReply("**"+TextUtility.flexFormToProper(pokemon.getName())+"** can be found in **"+(encounterDataFromVersion.size())+
					"** location(s) in **"+TextUtility.flexFormToProper(versionDBForm)+"** version");
			reply.setEmbed(formatEmbed(encounterDataFromVersion, versionDBForm, pokemon));
			
			return Mono.just(reply);
		} 
		catch(Exception e)
		{ 
			return Mono.just(this.createErrorResponse(input, e));
		}
	}
	
	private EmbedCreateSpec formatEmbed(List<EncounterPotential> encounterDataFromVersion, String version, Pokemon pokemon) throws ServiceException 
	{
		EmbedCreateSpec eBuilder = new EmbedCreateSpec();	
		StringBuilder sBuilder;
		Set<String> detailsList; 
		VersionDetail vDetails;
		ColorService colorService;
		
		for(EncounterPotential potential : encounterDataFromVersion)
		{
			detailsList = new HashSet<String>(); 
			vDetails = getVersionDetailFromVersion(potential, version).get(); //Assume the Optional is not empty, it should have been previously checked
			
			sBuilder = new StringBuilder();
			sBuilder.append(String.format("`|Max Encounter Rate: %-5s|`\n", vDetails.getMaxChance() + "%"));
			for(EncounterDetail eDetails : vDetails.getEncounterDetails())
			{
				sBuilder.append("`|-------------------------|`\n");
				sBuilder.append(formatLevel(eDetails));
				sBuilder.append(String.format("`|Method: %-17s|`\n", TextUtility.flexFormToProper(eDetails.getMethod().getName())));
				sBuilder.append(String.format("`|Conditions: %-13s|`\n", formatConditions(eDetails)));
				sBuilder.append(String.format("`|Encounter Rate: %-9s|`\n",eDetails.getChance() + "%"));
			}
			
			detailsList.add(sBuilder.toString());
			
			eBuilder.addField(TextUtility.flexFormToProper(potential.getLocationArea().getName()), sBuilder.toString(), true);
		}
		
		//Add thumbnail
		eBuilder.setThumbnail(pokemon.getSprites().getFrontDefault());
		
		//Add adopter
		this.addAdopter(pokemon, eBuilder);
		
		colorService = (ColorService)services.getService(ServiceType.COLOR);
		eBuilder.setColor(colorService.getColorForVersion(version));
		return eBuilder;
	}
	
	private String formatLevel(EncounterDetail details)
	{
		if(details.getMaxLevel() == details.getMinLevel())
			return String.format("`|Level: %-18d|`\n", details.getMinLevel());
			
		return String.format("`|Levels: %2d/%-14d|`\n", details.getMinLevel(), details.getMaxLevel());
	}
	
	private String formatConditions(EncounterDetail details)
	{
		if(details.getConditionValues().isEmpty())
			return "None";
		
		StringBuilder builder = new StringBuilder();
		
		for(ConditionValue cond : details.getConditionValues())
			builder.append(TextUtility.flexFormToProper(cond.getName()) + " & ");
		
		return builder.substring(0, builder.length() - 3);
	}

	private List<EncounterPotential> getEncounterDataFromVersion(Encounter encounterData, String version)
	{
		List<EncounterPotential> result = new ArrayList<EncounterPotential>();
		Optional<VersionDetail> detailCheck;
		
		if(encounterData.getEncounterPotential() == null || encounterData.getEncounterPotential().isEmpty())
			return result;
		
		for(EncounterPotential potential : encounterData.getEncounterPotential())
		{
			detailCheck = getVersionDetailFromVersion(potential, version);
			if(detailCheck.isPresent())
				result.add(potential);
		}
			
		return result;
	}
	
	private Optional<VersionDetail> getVersionDetailFromVersion(EncounterPotential potential, String version) 
	{
		for(VersionDetail vDetail : potential.getVersionDetails())
			if(TextUtility.flexToDBForm(vDetail.getVersion().getName()).equals(version))
				return Optional.of(vDetail);
		
		return Optional.empty();
	}
}
