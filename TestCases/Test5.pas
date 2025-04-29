program TestForLoop;
var
	I: Integer;
begin
	for I := 10 DOWNTO 1 do
  begin
    IF I = 3 THEN
    BEGIN
        break;
    END;
    IF I = 8 THEN
    BEGIN
        continue;
    END;
    WRITELN(I);
  end;
end.

