package skaro.pokedex.input_processor.arguments;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import skaro.pokedex.data_processor.TextFormatter;
import skaro.pokedex.input_processor.SpellChecker;

public class PokemonArgument extends AbstractArgument
{
	public PokemonArgument()
	{
		
	};
	
	public void setUp(String argument)
	{
		//Utility variables
		SpellChecker sc = SpellChecker.getInstance();
		
		//Set up argument
		this.dbForm = TextFormatter.dbFormat(argument);
		this.cat = ArgumentCategory.POKEMON;
		this.rawInput = argument;
		
		//Check if resource is recognized. If it is not recognized, attempt to spell check it.
		//If it is still not recognized, then return the argument as invalid (default)
		if(!isPokemon(this.dbForm))
		{
			String correction;
			correction = sc.spellCheckPokemon(argument);
			
			if(!isPokemon(correction))
			{
				this.valid = false;
				return;
			}
			
			this.dbForm = TextFormatter.dbFormat(correction).intern();
			this.rawInput = correction.intern();
			this.spellChecked = true;
		}
		
		this.valid = true;
		this.flexForm = sqlManager.getPokemonFlexForm(dbForm).get();
	}
	
	private boolean isPokemon(String s)
	{
		Optional<ResultSet> resultOptional = sqlManager.dbQuery("SELECT pid FROM Pokemon WHERE pid = '"+s+"';");
		boolean resourceExists = false;
		
		if(resultOptional.isPresent())
		{
			try 
			{ 
				resourceExists = resultOptional.get().next();
				resultOptional.get().close();
			} 
			catch(SQLException e)
			{ return resourceExists; }
		}

		return resourceExists;
	}
}